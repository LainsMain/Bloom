package be.lain.bloom.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import be.lain.bloom.BuildConfig
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class Release(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Hex SHA-256 published by GitHub for the asset, when available. */
    val sha256: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/** Semantic version compare: "1.10.0" > "1.9.2"; a leading "v" and pre-release suffixes are ignored. */
fun isNewer(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * Checks the public GitHub releases for a newer signed APK, downloads it, verifies it and hands it
 * to Android's package installer. Nothing about the user is sent; it's an anonymous public API call.
 */
@Singleton
class Updater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    /** Updates install over the release build only; debug builds use a different package. */
    val supported: Boolean get() = !BuildConfig.DEBUG

    fun checkOnLaunch() {
        if (!supported) return
        scope.launch {
            val s = settings.settings.first()
            if (!s.updateCheck) return@launch
            val found = runCatching { latest() }.getOrNull() ?: return@launch
            if (isNewer(found.version, BuildConfig.VERSION_NAME) && found.version != s.skippedVersion) {
                _state.value = UpdateState.Available(found)
            }
        }
    }

    fun checkNow() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching { latest() }.fold(
                onSuccess = { r -> if (isNewer(r.version, BuildConfig.VERSION_NAME)) UpdateState.Available(r) else UpdateState.UpToDate },
                onFailure = { e ->
                    UpdateState.Failed(if (e is java.io.IOException) "Couldn't reach GitHub. Check your connection." else "No release found: ${e.message}")
                },
            )
        }
    }

    fun dismiss() { _state.value = UpdateState.Idle }

    fun skip(r: Release) = scope.launch { settings.setSkippedVersion(r.version); _state.value = UpdateState.Idle }

    private suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Bloom-Android-Updater")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) error("nothing published yet")
            if (!resp.isSuccessful) error("GitHub answered ${resp.code}")
            val o = json.parseToJsonElement(resp.body!!.string()).jsonObject
            if (o["draft"]?.jsonPrimitive?.booleanOrNull == true || o["prerelease"]?.jsonPrimitive?.booleanOrNull == true) error("Not a stable release")
            val tag = o["tag_name"]!!.jsonPrimitive.content
            val asset = o["assets"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
                ?: error("No APK in the release")
            Release(
                version = tag.removePrefix("v"),
                tag = tag,
                notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
                apkSize = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                sha256 = asset["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            )
        }
    }

    fun download(r: Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(r, 0f)
            val result = runCatching { fetchAndVerify(r) }
            result.onSuccess { file -> install(r, file) }
                .onFailure { e -> _state.value = UpdateState.Failed(e.message ?: "Download failed", r) }
        }
    }

    private suspend fun fetchAndVerify(r: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "bloom-${r.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "Bloom-Android-Updater").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: r.apkSize
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = 0f
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            if (p - lastShown >= 0.01f) { lastShown = p; _state.value = UpdateState.Downloading(r, p) }
                        }
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && r.sha256 != hex) { file.delete(); error("The download didn't match GitHub's checksum") }
        verifyPackage(file)
        file
    }

    /** Same app, newer version, signed with the same key as the installed one. */
    private fun verifyPackage(file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = archiveInfo(pm, file, flags) ?: run { file.delete(); error("That file isn't a valid app") }
        val installed = installedInfo(pm, flags)
        if (archive.packageName != context.packageName) { file.delete(); error("The download is a different app") }
        if (archive.longVersionCode <= installed.longVersionCode) { file.delete(); error("The download isn't newer than this version") }
        if (signers(archive) != signers(installed) || signers(archive).isEmpty()) { file.delete(); error("The download isn't signed by Bloom's key") }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File, flags: Int): PackageInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(file.path, flags)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, flags: Int): PackageInfo =
        if (android.os.Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(context.packageName, flags)

    private fun signers(p: PackageInfo): Set<String> {
        val info = p.signingInfo ?: return emptySet()
        val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return certs.orEmpty().map { c -> MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows Bloom to install updates. */
    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun install(r: Release, file: File) {
        if (!canInstall()) {
            _state.value = UpdateState.Failed("Allow Bloom to install updates, then tap Update again.", r)
            openInstallPermission()
            return
        }
        _state.value = UpdateState.Installing(r)
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pi.intentSender)
            }
        }.onFailure { e -> _state.value = UpdateState.Failed(e.message ?: "Couldn't start the installer", r) }
    }

    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        val r = (state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = r?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(message ?: "The update couldn't be installed", r)
        }
    }
}

/** Receives the package installer's progress (confirmation needed, success, failure). */
@AndroidEntryPoint
class InstallResultReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: Updater

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}
