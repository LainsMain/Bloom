package be.lain.bloom.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads product barcodes (EAN/UPC) from photos on the phone with ML Kit; nothing is uploaded.
 * Photos are scanned at full resolution when attached (the stored copy is downscaled for the AI),
 * with a few crops and rotations as fallbacks for small or tilted codes.
 */
@Singleton
class PhotoBarcodes @Inject constructor(@ApplicationContext private val context: Context) {
    private val found = ConcurrentHashMap<String, List<String>>()

    private fun scanner() = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build(),
    )

    /** Scans the original picture behind [storedPath] and remembers the result for that stored copy. */
    suspend fun scanOriginal(original: Uri, storedPath: String): List<String> = withContext(Dispatchers.IO) {
        val codes = scanBitmaps { decode(original) }.ifEmpty { scanBitmaps { BitmapFactory.decodeFile(storedPath) } }
        found[storedPath] = codes
        codes
    }

    /** Scans any picture (e.g. from the gallery) without remembering it. */
    suspend fun scanUri(uri: Uri): List<String> = withContext(Dispatchers.IO) { scanBitmaps { decode(uri) } }

    /** Codes for stored photos: remembered from the original when possible, otherwise scanned now. */
    suspend fun codesFor(paths: List<String>): List<String> = withContext(Dispatchers.IO) {
        paths.flatMap { p -> found[p] ?: scanBitmaps { BitmapFactory.decodeFile(p) }.also { found[p] = it } }.distinct()
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; only a missing stream is a failure.
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Keep up to ~3000 px: plenty for a barcode, without huge memory use.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 3000) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)
        return if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    /** Whole image, then the centre (codes are often small), then rotated 90°, stopping at the first hit. */
    private suspend fun scanBitmaps(load: () -> Bitmap?): List<String> {
        val bmp = runCatching { load() }.getOrNull() ?: return emptyList()
        val scanner = scanner()
        try {
            val attempts = sequence {
                yield(bmp)
                val w = bmp.width; val h = bmp.height
                yield(Bitmap.createBitmap(bmp, w / 6, h / 6, w * 2 / 3, h * 2 / 3))
                yield(Bitmap.createBitmap(bmp, w / 4, h / 4, w / 2, h / 2))
                yield(Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(90f) }, true))
            }
            for (b in attempts) {
                val codes = scan(scanner, b)
                if (codes.isNotEmpty()) return codes
            }
            return emptyList()
        } finally {
            scanner.close()
        }
    }

    private suspend fun scan(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, bmp: Bitmap): List<String> =
        suspendCancellableCoroutine { cont ->
            scanner.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { list -> cont.resumeWith(Result.success(list.mapNotNull { it.rawValue }.filter { it.all(Char::isDigit) })) }
                .addOnFailureListener { cont.resumeWith(Result.success(emptyList())) }
        }
}
