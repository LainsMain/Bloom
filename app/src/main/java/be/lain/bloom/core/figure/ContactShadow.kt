package be.lain.bloom.core.figure

import be.lain.bloom.domain.motion.Rig
import be.lain.bloom.domain.motion.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Paints a soft shadow under the figure from its skeleton: every body segment is a capsule whose
 * shadow is dark and tight where it touches the floor and fades and widens as it rises. Cheap enough
 * to repaint every frame, and independent of the GPU's shadow support.
 */
class ContactShadow(rig: Rig, val size: Int = 96) {
    private class Seg(val a: Int, val b: Int, val radius: Double, val extendB: Double = 0.0)

    private val segs: List<Seg>
    private val alpha = FloatArray(size * size)
    val bytes = ByteArray(size * size)

    init {
        fun j(n: String) = rig[n]
        val list = mutableListOf(
            Seg(j("pelvis"), j("spine_02"), 0.14),
            Seg(j("spine_02"), j("neck_01"), 0.14),
            Seg(j("neck_01"), j("Head"), 0.06),
            Seg(j("Head"), j("Head"), 0.10, extendB = 0.12),
        )
        for (s in listOf("l", "r")) {
            list += Seg(j("thigh_$s"), j("calf_$s"), 0.085)
            list += Seg(j("calf_$s"), j("foot_$s"), 0.055)
            list += Seg(j("foot_$s"), j("ball_$s"), 0.045)
            list += Seg(j("ball_$s"), j("ball_leaf_$s"), 0.035)
            list += Seg(j("upperarm_$s"), j("lowerarm_$s"), 0.05)
            list += Seg(j("lowerarm_$s"), j("hand_$s"), 0.04)
            list += Seg(j("hand_$s"), j("middle_01_$s"), 0.04)
        }
        segs = list
    }

    /**
     * Repaints for joint positions [world]. The texture covers a square of [extent] metres centred
     * on ([cx], [cz]); [toLight] (normalised) shifts shadows away from the key light.
     */
    fun paint(world: Array<Vec3>, cx: Double, cz: Double, extent: Double, toLight: Vec3) {
        alpha.fill(0f)
        val cell = extent / size
        val half = size / 2.0
        val lx = if (toLight.y > 0.2) -toLight.x / toLight.y * 0.45 else 0.0
        val lz = if (toLight.y > 0.2) -toLight.z / toLight.y * 0.45 else 0.0
        for (s in segs) {
            val pa = world[s.a]
            var pb = world[s.b]
            if (s.extendB > 0) pb = pb + Vec3(0.0, s.extendB, 0.0) // the head's centre sits above its joint
            val ha = max(0.0, pa.y - s.radius); val hb = max(0.0, pb.y - s.radius)
            if (min(ha, hb) > 0.9) continue
            // Projected onto the floor along (half) the light direction.
            val ax = pa.x + ha * lx; val az = pa.z + ha * lz
            val bx = pb.x + hb * lx; val bz = pb.z + hb * lz
            val maxSpread = s.radius + max(ha, hb) * 0.35 + 0.03
            val i0 = ((min(ax, bx) - maxSpread - cx) / cell + half).toInt().coerceIn(0, size - 1)
            val i1 = ((max(ax, bx) + maxSpread - cx) / cell + half).toInt().coerceIn(0, size - 1)
            val k0 = ((min(az, bz) - maxSpread - cz) / cell + half).toInt().coerceIn(0, size - 1)
            val k1 = ((max(az, bz) + maxSpread - cz) / cell + half).toInt().coerceIn(0, size - 1)
            val dx = bx - ax; val dz = bz - az
            val len2 = dx * dx + dz * dz
            for (k in k0..k1) {
                val qz = cz + (k + 0.5 - half) * cell
                for (i in i0..i1) {
                    val qx = cx + (i + 0.5 - half) * cell
                    val t = if (len2 < 1e-9) 0.0 else (((qx - ax) * dx + (qz - az) * dz) / len2).coerceIn(0.0, 1.0)
                    val px = ax + dx * t - qx; val pz = az + dz * t - qz
                    val d = sqrt(px * px + pz * pz)
                    val h = ha + (hb - ha) * t
                    val spread = s.radius + h * 0.35 + 0.03
                    if (d >= spread) continue
                    val fade = (1 - h / 0.9).coerceIn(0.0, 1.0)
                    val inner = spread * (0.55 - min(0.4, h)).coerceAtLeast(0.15)
                    val f = if (d <= inner) 1.0 else 1 - (d - inner) / (spread - inner)
                    val a = (fade * fade * f * f).toFloat()
                    val idx = k * size + i
                    if (a > alpha[idx]) alpha[idx] = a
                }
            }
        }
        for (i in alpha.indices) bytes[i] = (alpha[i] * 255f).toInt().coerceIn(0, 255).toByte()
    }
}
