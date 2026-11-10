package be.lain.bloom.motion

import be.lain.bloom.domain.motion.Glb
import be.lain.bloom.domain.motion.Prop
import be.lain.bloom.domain.motion.PropKind
import be.lain.bloom.domain.motion.Quat
import be.lain.bloom.domain.motion.Rig
import be.lain.bloom.domain.motion.SkeletonPose
import be.lain.bloom.domain.motion.Vec3
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Loads the figure glb for tests: rig, skinned meshes and their textures. */
class FigureAsset(file: File = locate()) {
    val glb = Glb(file.readBytes())
    val rig = Rig.from(glb)
    val meshes: List<SkinnedMesh>
    val joints: IntArray
    val inverseBind: Array<DoubleArray>

    class SkinnedMesh(val name: String, val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray, val jnt: IntArray, val wt: FloatArray, val idx: IntArray, val tex: BufferedImage?)

    init {
        val j = glb.json
        val skin = j["skins"]!!.jsonArray[0].jsonObject
        joints = skin["joints"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        val ib = glb.floats(skin["inverseBindMatrices"]!!.jsonPrimitive.int)
        inverseBind = Array(joints.size) { k -> DoubleArray(16) { ib[k * 16 + it].toDouble() } }
        val textures = j["textures"]!!.jsonArray
        val images = j["images"]!!.jsonArray
        val views = j["bufferViews"]!!.jsonArray
        fun image(tex: Int): BufferedImage {
            val img = images[textures[tex].jsonObject["source"]!!.jsonPrimitive.int].jsonObject
            val v = views[img["bufferView"]!!.jsonPrimitive.int].jsonObject
            val off = v["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
            val len = v["byteLength"]!!.jsonPrimitive.int
            val bytes = ByteArray(len); glb.bin.duplicate().apply { position(off) }.get(bytes)
            return ImageIO.read(ByteArrayInputStream(bytes))
        }
        val mats = j["materials"]!!.jsonArray
        meshes = glb.nodes.mapNotNull { n ->
            val o = n.jsonObject
            if (o["skin"] == null) return@mapNotNull null
            val mesh = j["meshes"]!!.jsonArray[o["mesh"]!!.jsonPrimitive.int].jsonObject
            val prim = mesh["primitives"]!!.jsonArray[0].jsonObject
            val a = prim["attributes"]!!.jsonObject
            val mat = mats[prim["material"]!!.jsonPrimitive.int].jsonObject
            val bt = mat["pbrMetallicRoughness"]?.jsonObject?.get("baseColorTexture")?.jsonObject?.get("index")?.jsonPrimitive?.int
            SkinnedMesh(
                mesh["name"]!!.jsonPrimitive.content,
                glb.floats(a["POSITION"]!!.jsonPrimitive.int), glb.floats(a["NORMAL"]!!.jsonPrimitive.int),
                glb.floats(a["TEXCOORD_0"]!!.jsonPrimitive.int), glb.ints(a["JOINTS_0"]!!.jsonPrimitive.int),
                glb.floats(a["WEIGHTS_0"]!!.jsonPrimitive.int), glb.ints(prim["indices"]!!.jsonPrimitive.int), bt?.let(::image),
            )
        }
    }

    /** Skinned world-space positions and normals for a solved pose. */
    fun skin(p: SkeletonPose, m: SkinnedMesh): Pair<FloatArray, FloatArray> {
        val mats = Array(joints.size) { k ->
            val node = joints[k]
            val r = (p.delta[node] * rig.restGlobalR[node]).normalized()
            mul(trs(p.world[node], r), inverseBind[k])
        }
        val n = m.pos.size / 3
        val op = FloatArray(n * 3); val on = FloatArray(n * 3)
        for (v in 0 until n) {
            var px = 0.0; var py = 0.0; var pz = 0.0; var nx = 0.0; var ny = 0.0; var nz = 0.0
            for (k in 0..3) {
                val w = m.wt[v * 4 + k].toDouble(); if (w <= 0) continue
                val M = mats[m.jnt[v * 4 + k]]
                val x = m.pos[v * 3].toDouble(); val y = m.pos[v * 3 + 1].toDouble(); val z = m.pos[v * 3 + 2].toDouble()
                px += w * (M[0] * x + M[4] * y + M[8] * z + M[12]); py += w * (M[1] * x + M[5] * y + M[9] * z + M[13]); pz += w * (M[2] * x + M[6] * y + M[10] * z + M[14])
                val a = m.nrm[v * 3].toDouble(); val b = m.nrm[v * 3 + 1].toDouble(); val c = m.nrm[v * 3 + 2].toDouble()
                nx += w * (M[0] * a + M[4] * b + M[8] * c); ny += w * (M[1] * a + M[5] * b + M[9] * c); nz += w * (M[2] * a + M[6] * b + M[10] * c)
            }
            op[v * 3] = px.toFloat(); op[v * 3 + 1] = py.toFloat(); op[v * 3 + 2] = pz.toFloat()
            on[v * 3] = nx.toFloat(); on[v * 3 + 1] = ny.toFloat(); on[v * 3 + 2] = nz.toFloat()
        }
        return op to on
    }

    companion object {
        fun locate(): File = listOf("src/main/assets/figure/figure.glb", "app/src/main/assets/figure/figure.glb").map(::File).first { it.isFile }

        /** Column-major 4x4. */
        fun trs(t: Vec3, q: Quat): DoubleArray {
            val (x, y, z, w) = q
            return doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
                t.x, t.y, t.z, 1.0,
            )
        }

        fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { i ->
            val c = i / 4; val r = i % 4
            (0..3).sumOf { k -> a[k * 4 + r] * b[c * 4 + k] }
        }
    }
}

/** Tiny orthographic software rasteriser for previewing poses in unit tests. */
class SoftRenderer(val w: Int, val h: Int, val yawDeg: Double, val pitchDeg: Double, val center: Vec3, val scale: Double) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    private val zb = DoubleArray(w * h) { Double.NEGATIVE_INFINITY }
    private val yaw = Math.toRadians(yawDeg); private val pitch = Math.toRadians(pitchDeg)
    // Camera basis: looking from direction (sin yaw, ., cos yaw) towards the centre.
    private val fwd = Vec3(-sin(yaw) * cos(pitch), -sin(pitch), -cos(yaw) * cos(pitch))
    private val right = Vec3(cos(yaw), 0.0, -sin(yaw))
    private val up = right cross fwd
    private val light = Vec3(0.4, 0.85, 0.5).normalized()

    fun project(p: Vec3): Triple<Double, Double, Double> {
        val d = p - center
        return Triple(w / 2.0 + (d dot right) * scale, h / 2.0 - (d dot up) * scale, -(d dot fwd))
    }

    /** Floor checker + soft top-down shadow from [shadow] points. */
    fun floor(shadow: List<Vec3>) {
        val cell = 0.04
        val heights = HashMap<Long, Double>()
        for (p in shadow) {
            val k = (Math.floorDiv((p.x / cell).toLong(), 1L) shl 32) xor (Math.floorDiv((p.z / cell).toLong(), 1L) and 0xffffffffL)
            heights[k] = min(heights[k] ?: 9.0, p.y)
        }
        for (py in 0 until h) for (px in 0 until w) {
            // Ray through pixel along fwd, intersect y = 0.
            val o = center + right * ((px - w / 2.0) / scale) + up * ((h / 2.0 - py) / scale)
            if (kotlin.math.abs(fwd.y) < 1e-6) { img.setRGB(px, py, 0xF2EEF0); continue }
            val t = -o.y / fwd.y
            val hit = o + fwd * t
            val chk = ((Math.floorDiv((hit.x / 0.25).toLong(), 1L) + Math.floorDiv((hit.z / 0.25).toLong(), 1L)) and 1L) == 0L
            var base = if (chk) 236.0 else 226.0
            val k = (Math.floorDiv((hit.x / cell).toLong(), 1L) shl 32) xor (Math.floorDiv((hit.z / cell).toLong(), 1L) and 0xffffffffL)
            heights[k]?.let { hy -> base *= 0.55 + 0.45 * (hy / 0.6).coerceIn(0.0, 1.0) }
            if (kotlin.math.abs(hit.x) < 0.005 || kotlin.math.abs(hit.z) < 0.005) base -= 30
            val v = base.toInt().coerceIn(0, 255)
            img.setRGB(px, py, (v shl 16) or (v shl 8) or v)
            zb[py * w + px] = -(hit - center dot fwd) - 1e-4
        }
    }

    fun mesh(pos: FloatArray, nrm: FloatArray, uv: FloatArray?, idx: IntArray, tex: BufferedImage?, flat: Int = 0xB0A8A4) {
        val n = pos.size / 3
        val sx = DoubleArray(n); val sy = DoubleArray(n); val sz = DoubleArray(n)
        for (i in 0 until n) { val (a, b, c) = project(Vec3(pos[i * 3].toDouble(), pos[i * 3 + 1].toDouble(), pos[i * 3 + 2].toDouble())); sx[i] = a; sy[i] = b; sz[i] = c }
        var t = 0
        while (t < idx.size) {
            val i0 = idx[t]; val i1 = idx[t + 1]; val i2 = idx[t + 2]; t += 3
            val minX = max(0, min(sx[i0], min(sx[i1], sx[i2])).toInt()); val maxX = min(w - 1, max(sx[i0], max(sx[i1], sx[i2])).toInt() + 1)
            val minY = max(0, min(sy[i0], min(sy[i1], sy[i2])).toInt()); val maxY = min(h - 1, max(sy[i0], max(sy[i1], sy[i2])).toInt() + 1)
            if (minX > maxX || minY > maxY) continue
            val d = (sy[i1] - sy[i2]) * (sx[i0] - sx[i2]) + (sx[i2] - sx[i1]) * (sy[i0] - sy[i2])
            if (kotlin.math.abs(d) < 1e-12) continue
            for (y in minY..maxY) for (x in minX..maxX) {
                val px = x + 0.5; val py = y + 0.5
                val w0 = ((sy[i1] - sy[i2]) * (px - sx[i2]) + (sx[i2] - sx[i1]) * (py - sy[i2])) / d
                val w1 = ((sy[i2] - sy[i0]) * (px - sx[i2]) + (sx[i0] - sx[i2]) * (py - sy[i2])) / d
                val w2 = 1 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = w0 * sz[i0] + w1 * sz[i1] + w2 * sz[i2]
                val zi = y * w + x
                if (z <= zb[zi]) continue
                zb[zi] = z
                val nx = w0 * nrm[i0 * 3] + w1 * nrm[i1 * 3] + w2 * nrm[i2 * 3]
                val ny = w0 * nrm[i0 * 3 + 1] + w1 * nrm[i1 * 3 + 1] + w2 * nrm[i2 * 3 + 1]
                val nz = w0 * nrm[i0 * 3 + 2] + w1 * nrm[i1 * 3 + 2] + w2 * nrm[i2 * 3 + 2]
                val nn = Vec3(nx, ny, nz).normalized()
                val facing = -(nn dot fwd)
                val lit = 0.38 + 0.55 * max(0.0, nn dot light) + 0.15 * max(0.0, facing)
                var col = flat
                if (tex != null && uv != null) {
                    val u = w0 * uv[i0 * 2] + w1 * uv[i1 * 2] + w2 * uv[i2 * 2]
                    val v = w0 * uv[i0 * 2 + 1] + w1 * uv[i1 * 2 + 1] + w2 * uv[i2 * 2 + 1]
                    col = tex.getRGB(((u - kotlin.math.floor(u)) * (tex.width - 1)).toInt(), ((v - kotlin.math.floor(v)) * (tex.height - 1)).toInt())
                }
                val r = (((col shr 16) and 255) * lit).toInt().coerceIn(0, 255)
                val g = (((col shr 8) and 255) * lit).toInt().coerceIn(0, 255)
                val b = ((col and 255) * lit).toInt().coerceIn(0, 255)
                img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
    }

    fun prop(p: Prop) {
        val c = Math.toRadians(p.yaw)
        val corners = ArrayList<Float>(); val normals = ArrayList<Float>(); val idx = ArrayList<Int>()
        val faces = listOf(Vec3.X to Vec3.Y, -Vec3.X to Vec3.Y, Vec3.Y to Vec3.Z, -Vec3.Y to Vec3.Z, Vec3.Z to Vec3.Y, -Vec3.Z to Vec3.Y)
        for ((nrm, upv) in faces) {
            val r = upv cross nrm
            val base = corners.size / 3
            for ((su, sv) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
                val local = (nrm * 0.5 + r * (0.5 * su) + upv * (0.5 * sv)).let { Vec3(it.x * p.size.x, it.y * p.size.y, it.z * p.size.z) }
                val rot = Vec3(local.x * cos(c) + local.z * sin(c), local.y, -local.x * sin(c) + local.z * cos(c)) + p.center
                val rn = Vec3(nrm.x * cos(c) + nrm.z * sin(c), nrm.y, -nrm.x * sin(c) + nrm.z * cos(c))
                corners += listOf(rot.x.toFloat(), rot.y.toFloat(), rot.z.toFloat()); normals += listOf(rn.x.toFloat(), rn.y.toFloat(), rn.z.toFloat())
            }
            idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        val color = when (p.kind) { PropKind.BOX -> 0x9E8C80; PropKind.MAT -> 0x8C5C85; PropKind.TOWEL -> 0xDADAD2 }
        mesh(corners.toFloatArray(), normals.toFloatArray(), null, idx.toIntArray(), null, color)
    }
}
