package be.lain.bloom

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.lain.bloom.data.ai.PhotoBarcodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Realistic product photos (busy background, tilted, blurred, JPEG): barcodes are read on the phone. */
@RunWith(AndroidJUnit4::class)
class PhotoBarcodesTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val tests = InstrumentationRegistry.getInstrumentation().context
    private val barcodes = PhotoBarcodes(target)

    private fun file(name: String): File = File(target.cacheDir, name).also { f ->
        tests.assets.open("barcodes/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
    }

    @Test fun readsSmallCodeFromFullResolutionPhoto() = runBlocking {
        assertEquals(listOf("5449000000996"), barcodes.scanUri(Uri.fromFile(file("small-code-4000px.jpg"))))
    }

    @Test fun readsMediumCodeFromFullResolutionPhoto() = runBlocking {
        assertEquals(listOf("5449000000996"), barcodes.scanUri(Uri.fromFile(file("medium-code-4000px.jpg"))))
    }

    @Test fun readsTheDownscaledChatCopy() = runBlocking {
        assertEquals(listOf("5449000000996"), barcodes.codesFor(listOf(file("small-code-1280px.jpg").path)))
    }

    @Test fun resultFromTheOriginalIsRememberedForTheStoredCopy() = runBlocking {
        assertEquals(listOf("5449000000996"), barcodes.scanOriginal(Uri.fromFile(file("small-code-4000px.jpg")), "stored.jpg"))
        // "stored.jpg" doesn't exist: the answer must come from memory, not a new scan.
        assertEquals(listOf("5449000000996"), barcodes.codesFor(listOf("stored.jpg")))
    }

    @Test fun noCodeInAPlainPhoto() = runBlocking {
        val plain = File(target.cacheDir, "plain.jpg")
        android.graphics.Bitmap.createBitmap(800, 600, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(120, 90, 60)) }
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, plain.outputStream())
        assertEquals(emptyList<String>(), barcodes.scanUri(Uri.fromFile(plain)))
    }
}
