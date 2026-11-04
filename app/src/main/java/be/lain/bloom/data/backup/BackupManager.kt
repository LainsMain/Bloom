package be.lain.bloom.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import be.lain.bloom.BuildConfig
import be.lain.bloom.data.db.BloomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Clock
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

class BackupTooNewException : Exception("This backup is from a newer version of Bloom. Update the app first.")

/**
 * Everything the user logged, in one password-encrypted file: the database, settings and photos.
 * The DeepSeek key is not included; it's bound to this phone's keystore and is re-entered after a restore.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BloomDatabase,
    private val clock: Clock,
) {
    private val folders = listOf("progress_photos", "chat_images")
    private val skippedDataStores = setOf("secrets.preferences_pb")

    fun suggestedName(): String = "bloom-backup-${java.time.LocalDate.now(clock)}.bloombackup"

    suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val snapshot = File(context.cacheDir, "backup-snapshot.db").apply { delete() }
        try {
            // A consistent copy even while the app keeps writing (WAL mode).
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snapshot.path.replace("'", "''")}'")
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open the file")
            BackupCrypto.encrypting(out.buffered(), password).use { enc ->
                ZipOutputStream(enc).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(
                        buildJsonObject {
                            put("app", "be.lain.bloom")
                            put("versionName", BuildConfig.VERSION_NAME)
                            put("versionCode", BuildConfig.VERSION_CODE)
                            put("dbVersion", db.openHelper.readableDatabase.version)
                            put("filesDir", context.filesDir.path)
                            put("createdAt", clock.instant().toString())
                        }.toString().toByteArray(),
                    )
                    zip.closeEntry()
                    addFile(zip, snapshot, "db/bloom.db")
                    File(context.filesDir, "datastore").listFiles()?.filter { it.isFile && it.name !in skippedDataStores }?.forEach {
                        addFile(zip, it, "datastore/${it.name}")
                    }
                    folders.forEach { name ->
                        File(context.filesDir, name).listFiles()?.filter { it.isFile }?.forEach { addFile(zip, it, "files/$name/${it.name}") }
                    }
                }
            }
        } finally {
            snapshot.delete()
            password.fill(' ')
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Decrypts and checks the backup in a staging folder, then replaces the app's data.
     * Nothing is touched until the whole file has been verified.
     */
    suspend fun restore(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
            BackupCrypto.decrypting(input.buffered(), password).use { dec ->
                ZipInputStream(dec).use { zip ->
                    val root = staging.canonicalPath + File.separator
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val target = File(staging, e.name)
                        // Refuse entries that would escape the staging folder.
                        if (!target.canonicalPath.startsWith(root)) throw NotABackupException()
                        if (e.isDirectory) { target.mkdirs(); continue }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val manifest = File(staging, "manifest.json").takeIf { it.isFile }?.readText()
                ?.let { Json.parseToJsonElement(it).jsonObject } ?: throw NotABackupException()
            if (manifest["app"]?.jsonPrimitive?.contentOrNull != "be.lain.bloom") throw NotABackupException()
            val backupDb = manifest["dbVersion"]?.jsonPrimitive?.intOrNull ?: throw NotABackupException()
            if (backupDb > db.openHelper.readableDatabase.version) throw BackupTooNewException()
            val stagedDb = File(staging, "db/bloom.db").takeIf { it.isFile } ?: throw NotABackupException()

            // Photos are referenced by absolute path; rewrite them for this install, and check the file is sound.
            val oldFiles = manifest["filesDir"]?.jsonPrimitive?.contentOrNull
            SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { s ->
                s.rawQuery("PRAGMA integrity_check", null).use { c -> if (!c.moveToFirst() || c.getString(0) != "ok") throw NotABackupException() }
                val newFiles = context.filesDir.path
                if (oldFiles != null && oldFiles != newFiles) {
                    runCatching { s.execSQL("UPDATE progress_photo SET path = replace(path, ?, ?)", arrayOf(oldFiles, newFiles)) }
                    runCatching { s.execSQL("UPDATE chat_message SET images = replace(images, ?, ?) WHERE images IS NOT NULL", arrayOf(oldFiles, newFiles)) }
                }
            }

            // Point of no return: swap the data in.
            db.close()
            val dbFile = context.getDatabasePath("bloom.db")
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            stagedDb.copyTo(dbFile, overwrite = true)
            val stores = File(context.filesDir, "datastore").apply { mkdirs() }
            stores.listFiles()?.filter { it.name !in skippedDataStores }?.forEach { it.delete() }
            File(staging, "datastore").listFiles()?.filter { it.name !in skippedDataStores }?.forEach { it.copyTo(File(stores, it.name), overwrite = true) }
            folders.forEach { name ->
                val dest = File(context.filesDir, name).apply { deleteRecursively(); mkdirs() }
                File(staging, "files/$name").listFiles()?.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
            }
        } finally {
            staging.deleteRecursively()
            password.fill(' ')
        }
    }

    /** Restarts into the restored data (in-memory caches of the old data must not survive). */
    fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
