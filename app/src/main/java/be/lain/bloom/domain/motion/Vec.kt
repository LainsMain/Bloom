package be.lain.bloom.domain.motion

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** World space for the figure: Y up, floor at y = 0, the figure faces +Z, its left side is +X. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val l = length; return if (l < 1e-12) this else Vec3(x / l, y / l, z / l) }
    fun lerp(o: Vec3, t: Double) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun withY(v: Double) = Vec3(x, v, z)
    /** Mirror across the figure's midline (left ↔ right). */
    fun mirrored() = Vec3(-x, y, z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
        fun of(x: Number, y: Number, z: Number) = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
    }
}

/** Unit quaternion (x, y, z, w) as in glTF. */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(q: Quat) = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z,
    )

    fun conj() = Quat(-x, -y, -z, w)
    fun normalized(): Quat { val l = sqrt(x * x + y * y + z * z + w * w); return Quat(x / l, y / l, z / l, w / l) }

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q×v) + 2 q×(q×v)
        val qv = Vec3(x, y, z)
        val t = (qv cross v) * 2.0
        return v + t * w + (qv cross t)
    }

    /** Mirror a rotation across the YZ plane (left ↔ right). */
    fun mirrored() = Quat(x, -y, -z, w)

    fun slerp(o: Quat, t: Double): Quat {
        var b = o
        var d = x * b.x + y * b.y + z * b.z + w * b.w
        if (d < 0) { b = Quat(-b.x, -b.y, -b.z, -b.w); d = -d }
        if (d > 0.9995) return Quat(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t).normalized()
        val th = acos(d.coerceIn(-1.0, 1.0)); val s = sin(th)
        val a = sin((1 - t) * th) / s; val c = sin(t * th) / s
        return Quat(x * a + b.x * c, y * a + b.y * c, z * a + b.z * c, w * a + b.w * c)
    }

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        fun axisAngle(axis: Vec3, radians: Double): Quat {
            val a = axis.normalized(); val s = sin(radians / 2)
            return Quat(a.x * s, a.y * s, a.z * s, cos(radians / 2))
        }

        fun rx(deg: Double) = axisAngle(Vec3.X, Math.toRadians(deg))
        fun ry(deg: Double) = axisAngle(Vec3.Y, Math.toRadians(deg))
        fun rz(deg: Double) = axisAngle(Vec3.Z, Math.toRadians(deg))

        /** Rotation taking the orthonormal frame (a0, b0) onto (a1, b1); b is re-orthogonalised against a. */
        fun fromFrames(a0: Vec3, b0: Vec3, a1: Vec3, b1: Vec3): Quat {
            val m0 = basis(a0, b0); val m1 = basis(a1, b1)
            // R = M1 * M0^T
            val r = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> m1[i][k] * m0[j][k] } } }
            return fromMatrix(r)
        }

        /** Shortest rotation from direction a to b. */
        fun between(a: Vec3, b: Vec3): Quat {
            val u = a.normalized(); val v = b.normalized()
            val d = u dot v
            if (d > 0.999999) return IDENTITY
            if (d < -0.999999) {
                val ortho = if (kotlin.math.abs(u.x) < 0.9) Vec3.X else Vec3.Y
                return axisAngle((u cross ortho).normalized(), Math.PI)
            }
            val c = u cross v
            return Quat(c.x, c.y, c.z, 1 + d).normalized()
        }

        /** Columns a, b', a×b' as rows-of-columns matrix m[row][col]. */
        private fun basis(a: Vec3, b: Vec3): Array<DoubleArray> {
            val e1 = a.normalized()
            val e2 = (b - e1 * (b dot e1)).normalized()
            val e3 = e1 cross e2
            return arrayOf(doubleArrayOf(e1.x, e2.x, e3.x), doubleArrayOf(e1.y, e2.y, e3.y), doubleArrayOf(e1.z, e2.z, e3.z))
        }

        fun fromMatrix(m: Array<DoubleArray>): Quat {
            val tr = m[0][0] + m[1][1] + m[2][2]
            return when {
                tr > 0 -> { val s = sqrt(tr + 1.0) * 2; Quat((m[2][1] - m[1][2]) / s, (m[0][2] - m[2][0]) / s, (m[1][0] - m[0][1]) / s, 0.25 * s) }
                m[0][0] > m[1][1] && m[0][0] > m[2][2] -> { val s = sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2; Quat(0.25 * s, (m[0][1] + m[1][0]) / s, (m[0][2] + m[2][0]) / s, (m[2][1] - m[1][2]) / s) }
                m[1][1] > m[2][2] -> { val s = sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2; Quat((m[0][1] + m[1][0]) / s, 0.25 * s, (m[1][2] + m[2][1]) / s, (m[0][2] - m[2][0]) / s) }
                else -> { val s = sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2; Quat((m[0][2] + m[2][0]) / s, (m[1][2] + m[2][1]) / s, 0.25 * s, (m[1][0] - m[0][1]) / s) }
            }.normalized()
        }
    }
}

/** Signed angle (radians) of the twist of [q] about [axis]. */
fun twistAngle(q: Quat, axis: Vec3): Double {
    val a = axis.normalized()
    val p = Vec3(q.x, q.y, q.z) dot a
    return 2 * atan2(p, q.w)
}

fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
