package be.lain.bloom.data.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : Exception("Wrong password, or the file is damaged")
class NotABackupException : Exception("This isn't a Bloom backup file")

/**
 * Password-encrypted backup stream: AES-256-GCM in 1 MiB chunks, key from PBKDF2-HMAC-SHA256.
 *
 * Layout: magic "BLOOMBK1", iterations (int), salt (16), nonce prefix (8), then chunks of
 * [length (int)][ciphertext + 16-byte tag]. Each chunk's nonce is prefix + counter, and its AAD
 * binds the header, the counter and a "last chunk" flag, so chunks can't be reordered, dropped
 * or truncated without failing authentication. Streaming keeps memory flat for large photo sets.
 */
object BackupCrypto {
    private val MAGIC = "BLOOMBK1".toByteArray(Charsets.US_ASCII)
    const val ITERATIONS = 210_000
    private const val CHUNK = 1 shl 20
    private const val TAG_BITS = 128

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, counter: Int) = ByteBuffer.allocate(12).put(prefix).putInt(counter).array()
    private fun aad(header: ByteArray, counter: Int, last: Boolean) =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(counter).put(if (last) 1 else 0).array()

    /** Returns an output stream that encrypts everything written to it into [out]. Close it to finish. */
    fun encrypting(out: OutputStream, password: CharArray, iterations: Int = ITERATIONS): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = ByteBuffer.allocate(MAGIC.size + 4 + 16 + 8).put(MAGIC).putInt(iterations).put(salt).put(prefix).array()
        val k = key(password, salt, iterations)
        val data = DataOutputStream(out)
        data.write(header)
        return object : OutputStream() {
            private val buf = ByteArray(CHUNK)
            private var n = 0
            private var counter = 0
            private var closed = false

            private fun flushChunk(last: Boolean) {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, counter)))
                c.updateAAD(aad(header, counter, last))
                val ct = c.doFinal(buf, 0, n)
                data.writeInt(ct.size)
                data.write(ct)
                counter++
                n = 0
            }

            override fun write(b: Int) {
                if (n == CHUNK) flushChunk(false)
                buf[n++] = b.toByte()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var left = len
                while (left > 0) {
                    if (n == CHUNK) flushChunk(false)
                    val take = minOf(left, CHUNK - n)
                    System.arraycopy(b, o, buf, n, take)
                    n += take; o += take; left -= take
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                flushChunk(true)
                data.flush()
                out.close()
            }
        }
    }

    /** Returns a stream of the decrypted content. Throws [WrongPasswordException] / [NotABackupException]. */
    fun decrypting(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try { data.readFully(magic) } catch (e: EOFException) { throw NotABackupException() }
        if (!magic.contentEquals(MAGIC)) throw NotABackupException()
        val iterations = data.readInt()
        if (iterations !in 10_000..5_000_000) throw NotABackupException()
        val salt = ByteArray(16).also(data::readFully)
        val prefix = ByteArray(8).also(data::readFully)
        val header = ByteBuffer.allocate(MAGIC.size + 4 + 16 + 8).put(MAGIC).putInt(iterations).put(salt).put(prefix).array()
        val k = key(password, salt, iterations)

        return object : InputStream() {
            private var plain = ByteArray(0)
            private var pos = 0
            private var counter = 0
            private var done = false

            private fun next(): Boolean {
                if (done) return false
                val len = try { data.readInt() } catch (e: EOFException) { throw WrongPasswordException() }
                if (len < 16 || len > CHUNK + 16) throw WrongPasswordException()
                val ct = ByteArray(len).also { try { data.readFully(it) } catch (e: EOFException) { throw WrongPasswordException() } }
                plain = decryptChunk(ct)
                pos = 0
                counter++
                return true
            }

            /** Exactly one of the two "last chunk" flags authenticates; that's how the end is found. */
            private fun decryptChunk(ct: ByteArray): ByteArray {
                for (last in listOf(false, true)) {
                    try {
                        val c = Cipher.getInstance("AES/GCM/NoPadding")
                        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, counter)))
                        c.updateAAD(aad(header, counter, last))
                        val out = c.doFinal(ct)
                        if (last) done = true
                        return out
                    } catch (e: AEADBadTagException) {
                        // try the other flag
                    }
                }
                throw WrongPasswordException()
            }

            override fun read(): Int {
                while (pos >= plain.size) { if (!next()) return -1 }
                return plain[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= plain.size) { if (!next()) return -1 }
                val take = minOf(len, plain.size - pos)
                System.arraycopy(plain, pos, b, off, take)
                pos += take
                return take
            }

            override fun close() = input.close()
        }
    }
}
