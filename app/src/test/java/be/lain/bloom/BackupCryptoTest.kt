package be.lain.bloom

import be.lain.bloom.data.backup.BackupCrypto
import be.lain.bloom.data.backup.NotABackupException
import be.lain.bloom.data.backup.WrongPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class BackupCryptoTest {
    private fun encrypt(data: ByteArray, pw: String = "correct horse"): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypting(out, pw.toCharArray(), iterations = 10_000).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decrypt(enc: ByteArray, pw: String = "correct horse"): ByteArray =
        BackupCrypto.decrypting(ByteArrayInputStream(enc), pw.toCharArray()).use { it.readBytes() }

    @Test fun roundTripsSmallEmptyAndMultiChunk() {
        for (size in listOf(0, 17, 1 shl 20, (1 shl 20) + 1, 3_500_000)) {
            val data = Random(size).nextBytes(size)
            assertArrayEquals("size $size", data, decrypt(encrypt(data)))
        }
    }

    @Test(expected = WrongPasswordException::class)
    fun wrongPasswordFails() { decrypt(encrypt("hello".toByteArray()), "wrong") }

    @Test(expected = WrongPasswordException::class)
    fun truncationIsDetected() {
        val enc = encrypt(Random(1).nextBytes(2_500_000))
        // Drop the final chunk entirely: everything left still authenticates, but the end marker is missing.
        decrypt(enc.copyOf(enc.size - 500_000))
    }

    @Test(expected = WrongPasswordException::class)
    fun tamperingIsDetected() {
        val enc = encrypt(Random(2).nextBytes(1000))
        enc[enc.size - 20] = (enc[enc.size - 20].toInt() xor 1).toByte()
        decrypt(enc)
    }

    @Test(expected = NotABackupException::class)
    fun otherFilesAreRejected() { decrypt("PK\u0003\u0004 not a backup".toByteArray()) }
}
