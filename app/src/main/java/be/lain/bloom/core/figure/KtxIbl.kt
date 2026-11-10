package be.lain.bloom.core.figure

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the image-based light written by Filament's `cmgen --format=ktx`: a KTX1 cubemap of
 * R11F_G11F_B10F mip levels whose metadata carries the irradiance spherical harmonics ("sh").
 */
object KtxIbl {
    class Loaded(val light: IndirectLight, val cubemap: Texture)

    fun load(engine: Engine, bytes: ByteArray): Loaded {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // After the 12-byte identifier and the endianness word: glType, glTypeSize, glFormat,
        // glInternalFormat, glBaseInternalFormat, width, height, depth, arrayElements, faces, levels, kvBytes.
        val h = IntArray(12) { b.getInt(16 + it * 4) }
        val width = h[5]; val height = h[6]; val faces = h[9]; val levels = maxOf(1, h[10]); val kvBytes = h[11]
        require(faces == 6) { "IBL must be a cubemap" }
        var p = 64
        val sh = parseSh(b, p, kvBytes)
        p += kvBytes
        val tex = Texture.Builder().width(width).height(height).levels(levels)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP).format(Texture.InternalFormat.R11F_G11F_B10F).build(engine)
        for (level in 0 until levels) {
            val faceSize = b.getInt(p); p += 4
            val dim = maxOf(1, width shr level)
            val data = ByteBuffer.allocateDirect(faceSize * 6).order(ByteOrder.nativeOrder())
            for (f in 0 until 6) {
                data.put(bytes, p, faceSize)
                p += faceSize
                p += (4 - faceSize % 4) % 4 // cube padding
            }
            data.flip()
            tex.setImage(engine, level, 0, 0, 0, dim, dim, 6,
                Texture.PixelBufferDescriptor(data, Texture.Format.RGB, Texture.Type.UINT_10F_11F_11F_REV))
        }
        val light = IndirectLight.Builder().reflections(tex).irradiance(3, sh).build(engine)
        return Loaded(light, tex)
    }

    /** cmgen stores the 9 RGB SH coefficients as text under the "sh" key. */
    private fun parseSh(b: ByteBuffer, start: Int, length: Int): FloatArray {
        var p = start
        while (p < start + length) {
            val size = b.getInt(p); p += 4
            val raw = ByteArray(size); b.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(p) }.get(raw)
            val zero = raw.indexOf(0)
            if (zero > 0 && String(raw, 0, zero) == "sh") {
                val values = String(raw, zero + 1, size - zero - 1).trim('\u0000', ' ', '\n')
                    .split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                if (values.size >= 27) return values.take(27).toFloatArray()
            }
            p += size + (4 - size % 4) % 4
        }
        return DEFAULT_SH
    }

    /** SH of the bundled studio environment (from cmgen's sh.txt), used if the metadata is missing. */
    private val DEFAULT_SH = floatArrayOf(
        0.7127f, 0.6920f, 0.6840f, 0.4368f, 0.4228f, 0.4153f, 0.0774f, 0.0800f, 0.0762f,
        -0.0036f, -0.0094f, -0.0255f, 0.0480f, 0.0437f, 0.0333f, 0.0794f, 0.0795f, 0.0731f,
        0.0038f, 0.0027f, 0.0026f, 0.0486f, 0.0422f, 0.0325f, -0.0107f, -0.0084f, -0.0048f,
    )
}
