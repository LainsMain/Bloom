package be.lain.bloom.core.figure

import android.content.Context
import be.lain.bloom.domain.motion.Glb
import be.lain.bloom.domain.motion.GuideFrame
import be.lain.bloom.domain.motion.Motion
import be.lain.bloom.domain.motion.MotionLibrary
import be.lain.bloom.domain.motion.MotionStyle
import be.lain.bloom.domain.motion.Rig
import be.lain.bloom.domain.motion.Solver
import be.lain.bloom.domain.motion.Vec3
import java.util.concurrent.ConcurrentHashMap

/** The demo figure's data, loaded once per process: glb bytes, rig, solver and every exercise motion. */
class FigureModel private constructor(context: Context) {
    val glbBytes: ByteArray = context.assets.open("figure/figure.glb").use { it.readBytes() }
    val shadowMaterial: ByteArray = context.assets.open("figure/contact_shadow.filamat").use { it.readBytes() }
    val ibl: ByteArray = context.assets.open("figure/studio_ibl.ktx").use { it.readBytes() }
    val rig: Rig = Rig.from(Glb(glbBytes))
    val solver = Solver(rig)
    val library = MotionLibrary(solver)
    private val bounds = ConcurrentHashMap<String, Bounds>()

    fun motion(id: String): Motion? = library[id]

    /** Box around every joint over the whole cycle (both sides), padded for the body's surface. */
    fun bounds(m: Motion): Bounds = bounds.getOrPut(m.id) {
        var lo = Vec3(9.0, 9.0, 9.0); var hi = Vec3(-9.0, -9.0, -9.0)
        val head = rig["Head"]
        val samples = if (m.tempo.style == MotionStyle.HOLD) 1 else 10
        for (mirror in listOf(false, true)) for (k in 0 until samples) {
            val sp = solver.solve(m.pose(GuideFrame(k * m.tempo.cycleSeconds / samples, mirrored = mirror)))
            sp.world.forEachIndexed { i, p ->
                if (rig.names[i].startsWith("prop")) return@forEachIndexed
                val pad = if (i == head) 0.2 else 0.07
                lo = Vec3(minOf(lo.x, p.x - pad), minOf(lo.y, p.y - pad), minOf(lo.z, p.z - pad))
                hi = Vec3(maxOf(hi.x, p.x + pad), maxOf(hi.y, p.y + pad), maxOf(hi.z, p.z + pad))
            }
        }
        m.props.forEach { pr ->
            lo = Vec3(minOf(lo.x, pr.center.x - pr.size.x / 2), minOf(lo.y, pr.center.y - pr.size.y / 2), minOf(lo.z, pr.center.z - pr.size.z / 2))
            hi = Vec3(maxOf(hi.x, pr.center.x + pr.size.x / 2), maxOf(hi.y, pr.center.y + pr.size.y / 2), maxOf(hi.z, pr.center.z + pr.size.z / 2))
        }
        Bounds(Vec3(lo.x, maxOf(0.0, lo.y), lo.z), hi)
    }

    data class Bounds(val lo: Vec3, val hi: Vec3) {
        val center: Vec3 get() = (lo + hi) * 0.5
    }

    companion object {
        @Volatile private var instance: FigureModel? = null
        fun get(context: Context): FigureModel = instance ?: synchronized(this) {
            instance ?: FigureModel(context.applicationContext).also { instance = it }
        }
    }
}
