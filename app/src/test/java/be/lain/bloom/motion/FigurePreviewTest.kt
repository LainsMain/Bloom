package be.lain.bloom.motion

import be.lain.bloom.domain.motion.GuideFrame
import be.lain.bloom.domain.motion.MotionLibrary
import be.lain.bloom.domain.motion.MotionStyle
import be.lain.bloom.domain.motion.Solver
import be.lain.bloom.domain.motion.Vec3
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Renders contact sheets of the exercise animations for review:
 *   ./gradlew :app:testDebugUnitTest --tests '*FigurePreviewTest*' -Pbloom.preview=pushup,glute-bridge   (or "all")
 * Output: app/build/figure-preview/<id>.png
 */
class FigurePreviewTest {
    @Test fun renderContactSheets() {
        val which = System.getProperty("bloom.preview").orEmpty()
        assumeTrue("set -Pbloom.preview=ids|all to render", which.isNotBlank())
        val asset = FigureAsset()
        val solver = Solver(asset.rig)
        val lib = MotionLibrary(solver)
        val ids = if (which == "all") lib.all.keys.toList() else which.split(",").map { it.trim() }
        val out = File("build/figure-preview").apply { mkdirs() }
        for (id in ids) {
            val m = lib[id] ?: error("no motion $id")
            val frames = if (m.tempo.style == MotionStyle.HOLD) 2 else 6
            val c = m.tempo.cycleSeconds
            val poses = (0 until frames).map { k -> m.pose(GuideFrame(k * c / frames)) }
            val solved = poses.map { solver.solve(it) }
            val skinned = solved.map { sp -> asset.meshes.map { mesh -> asset.skin(sp, mesh) } }
            val cell = 340
            val views = listOf(m.camera.yaw to m.camera.pitch, 90.0 to 4.0)
            val sheet = BufferedImage(cell * frames, cell * views.size, BufferedImage.TYPE_INT_RGB)
            val g = sheet.createGraphics()
            views.forEachIndexed { row, (yaw, pitch) ->
                // Fit all frames (and props) in the same framing.
                var lo = Vec3(9.0, 9.0, 9.0); var hi = Vec3(-9.0, -9.0, -9.0)
                val probe = SoftRenderer(cell, cell, yaw, pitch, Vec3.ZERO, 1.0)
                var minX = 1e9; var maxX = -1e9; var minY = 1e9; var maxY = -1e9
                skinned.forEach { f -> f.forEach { (p, _) -> var i = 0; while (i < p.size) {
                    val (sx, sy, _) = probe.project(Vec3(p[i].toDouble(), p[i + 1].toDouble(), p[i + 2].toDouble()))
                    minX = min(minX, sx); maxX = max(maxX, sx); minY = min(minY, sy); maxY = max(maxY, sy)
                    lo = Vec3(min(lo.x, p[i].toDouble()), min(lo.y, p[i + 1].toDouble()), min(lo.z, p[i + 2].toDouble()))
                    hi = Vec3(max(hi.x, p[i].toDouble()), max(hi.y, p[i + 1].toDouble()), max(hi.z, p[i + 2].toDouble()))
                    i += 3 } } }
                val center = (lo + hi) * 0.5
                val scale = 0.86 * cell / max(maxX - minX, maxY - minY)
                for (k in 0 until frames) {
                    val r = SoftRenderer(cell, cell, yaw, pitch, center, scale)
                    val pts = ArrayList<Vec3>()
                    skinned[k].forEach { (p, _) -> var i = 0; while (i < p.size) { pts += Vec3(p[i].toDouble(), p[i + 1].toDouble(), p[i + 2].toDouble()); i += 9 } }
                    r.floor(pts)
                    m.props(false).forEach { r.prop(it) }
                    asset.meshes.forEachIndexed { mi, mesh -> val (p, n) = skinned[k][mi]; r.mesh(p, n, mesh.uv, mesh.idx, mesh.tex) }
                    g.drawImage(r.img, k * cell, row * cell, null)
                    if (row == 0) {
                        g.color = Color(60, 40, 60); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
                        var minY2 = 9.0; var at = Vec3.ZERO
                        skinned[k].forEach { (p, _) -> for (i in 1 until p.size step 3) if (p[i] < minY2) { minY2 = p[i].toDouble(); at = Vec3(p[i - 1].toDouble(), p[i].toDouble(), p[i + 1].toDouble()) } }
                        val near = asset.rig.names.indices.filter { asset.rig.names[it].let { n -> !n.contains("leaf") && n.isNotEmpty() && n != "root" && n != "Armature" && !n.startsWith("prop") } }
                            .minBy { (solved[k].world[it] - at).length }.let { asset.rig.names[it] }
                        val e = solved[k].ikError.max()
                        g.drawString("t=%.2f minY=%.3f@%s ik=%.3f".format(k * c / frames, minY2, near, e), k * cell + 4, 16)
                    }
                }
            }
            g.color = Color(60, 40, 60); g.font = Font(Font.SANS_SERIF, Font.BOLD, 15); g.drawString(id, 6, cell * views.size - 8)
            ImageIO.write(sheet, "png", File(out, "$id.png"))
            println("preview: ${File(out, "$id.png").absolutePath}")
        }
    }
}
