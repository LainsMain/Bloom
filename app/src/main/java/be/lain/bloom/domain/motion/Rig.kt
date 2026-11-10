package be.lain.bloom.domain.motion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A parsed binary glTF (.glb): its JSON and binary chunk. */
class Glb(bytes: ByteArray) {
    val json: JsonObject
    val bin: ByteBuffer

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(0) == 0x46546C67) { "Not a glb file" }
        val jsonLen = b.getInt(12)
        json = Json.parseToJsonElement(String(bytes, 20, jsonLen, Charsets.UTF_8)).jsonObject
        val binLen = b.getInt(20 + jsonLen)
        bin = ByteBuffer.wrap(bytes, 28 + jsonLen, binLen).slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    val nodes: JsonArray get() = json["nodes"]!!.jsonArray

    /** Reads an accessor as floats (normalised integers are scaled to 0..1, other integers kept as values). */
    fun floats(accessor: Int): FloatArray {
        val a = json["accessors"]!!.jsonArray[accessor].jsonObject
        val view = json["bufferViews"]!!.jsonArray[a["bufferView"]!!.jsonPrimitive.int].jsonObject
        val n = when (a["type"]!!.jsonPrimitive.content) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error("type") }
        val count = a["count"]!!.jsonPrimitive.int
        val ctype = a["componentType"]!!.jsonPrimitive.int
        val off = (view["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0) + (a["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0)
        val size = when (ctype) { 5126, 5125 -> 4; 5123, 5122 -> 2; else -> 1 }
        val stride = view["byteStride"]?.jsonPrimitive?.intOrNull ?: (size * n)
        val normalized = a["normalized"]?.jsonPrimitive?.contentOrNull == "true"
        val out = FloatArray(count * n)
        for (i in 0 until count) for (k in 0 until n) {
            val p = off + i * stride + k * size
            out[i * n + k] = when (ctype) {
                5126 -> bin.getFloat(p)
                5125 -> bin.getInt(p).toFloat()
                5123 -> (bin.getShort(p).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                5121 -> (bin.get(p).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                else -> error("component type $ctype")
            }
        }
        return out
    }

    fun ints(accessor: Int): IntArray = floats(accessor).let { f -> IntArray(f.size) { f[it].toInt() } }
}

/**
 * The figure's skeleton in its rest (bind) pose. Node indices match the glb, so the renderer can
 * map them straight onto its own entities.
 */
class Rig(
    val names: List<String>,
    val parent: IntArray,
    val restT: List<Vec3>,
    val restR: List<Quat>,
    val restS: List<Vec3>,
) {
    val size = names.size
    private val index = names.withIndex().associate { (i, n) -> n to i }
    operator fun get(name: String): Int = index[name] ?: error("No joint $name")
    fun find(name: String): Int? = index[name]

    /** Parents before children. */
    val order: IntArray
    val restGlobalR: Array<Quat>
    val restGlobalP: Array<Vec3>

    init {
        val children = Array(size) { mutableListOf<Int>() }
        for (i in 0 until size) if (parent[i] >= 0) children[parent[i]] += i
        val o = ArrayList<Int>(size)
        val queue = ArrayDeque((0 until size).filter { parent[it] < 0 })
        while (queue.isNotEmpty()) { val i = queue.removeFirst(); o += i; queue.addAll(children[i]) }
        order = o.toIntArray()
        restGlobalR = Array(size) { Quat.IDENTITY }
        restGlobalP = Array(size) { Vec3.ZERO }
        for (i in order) {
            val p = parent[i]
            if (p < 0) { restGlobalR[i] = restR[i]; restGlobalP[i] = restT[i] }
            else {
                restGlobalR[i] = (restGlobalR[p] * restR[i]).normalized()
                restGlobalP[i] = restGlobalP[p] + restGlobalR[p].rotate(restT[i].scaled(restS[p]))
            }
        }
    }

    fun rest(name: String): Vec3 = restGlobalP[get(name)]
    fun dist(a: String, b: String): Double = (rest(a) - rest(b)).length

    companion object {
        fun from(glb: Glb): Rig {
            val nodes = glb.nodes
            val n = nodes.size
            val parent = IntArray(n) { -1 }
            nodes.forEachIndexed { i, e -> e.jsonObject["children"]?.jsonArray?.forEach { parent[it.jsonPrimitive.int] = i } }
            fun vec(o: JsonObject, key: String, def: Vec3) = o[key]?.jsonArray?.let { Vec3(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double) } ?: def
            return Rig(
                names = nodes.map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "" },
                parent = parent,
                restT = nodes.map { vec(it.jsonObject, "translation", Vec3.ZERO) },
                restR = nodes.map { o -> o.jsonObject["rotation"]?.jsonArray?.let { Quat(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double, it[3].jsonPrimitive.double).normalized() } ?: Quat.IDENTITY },
                restS = nodes.map { vec(it.jsonObject, "scale", Vec3(1.0, 1.0, 1.0)) },
            )
        }
    }
}

private fun Vec3.scaled(s: Vec3) = Vec3(x * s.x, y * s.y, z * s.z)
