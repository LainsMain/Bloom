package be.lain.bloom.domain.motion

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Result of solving a [Pose]: glTF-ready local rotations plus world joint positions. */
class SkeletonPose(
    /** Local rotation of every rig node. */
    val local: Array<Quat>,
    /** Local translation of every rig node (rest values except the pelvis). */
    val translation: Array<Vec3>,
    /** World position of every rig node. */
    val world: Array<Vec3>,
    /** World rotation relative to the rest pose. */
    val delta: Array<Quat>,
    /** How far each IK end missed its target (m): legL, legR, armL, armR. */
    val ikError: DoubleArray,
)

/**
 * Turns body-level [Pose]s into joint rotations for the Quaternius/UE-style rig (pelvis, spine_01-03,
 * neck_01, Head, clavicle/upperarm/lowerarm/hand, thigh/calf/foot/ball, fingers).
 *
 * Rotations are built as world "deltas" from the rest (T-) pose: a child's delta is its parent's delta
 * times an anatomical rotation expressed in the rest pose's world axes, so the rig's own bone axis
 * conventions never matter. Inverse kinematics sets limb deltas directly from target directions.
 */
class Solver(val rig: Rig) {
    private val pelvis = rig["pelvis"]
    private val spine = intArrayOf(rig["spine_01"], rig["spine_02"], rig["spine_03"])
    private val spineShare = doubleArrayOf(0.3, 0.35, 0.35)
    private val neck = rig["neck_01"]
    private val head = rig["Head"]

    private inner class LegJ(s: String) {
        val thigh = rig["thigh_$s"]; val calf = rig["calf_$s"]; val foot = rig["foot_$s"]; val ball = rig["ball_$s"]
        val a = (rig.restGlobalP[calf] - rig.restGlobalP[thigh]).length
        val b = (rig.restGlobalP[foot] - rig.restGlobalP[calf]).length
        val u0 = (rig.restGlobalP[calf] - rig.restGlobalP[thigh]).normalized()
        val l0 = (rig.restGlobalP[foot] - rig.restGlobalP[calf]).normalized()
    }

    private inner class ArmJ(val s: String) {
        val clav = rig["clavicle_$s"]; val upper = rig["upperarm_$s"]; val lower = rig["lowerarm_$s"]; val hand = rig["hand_$s"]
        val a = (rig.restGlobalP[lower] - rig.restGlobalP[upper]).length
        val b = (rig.restGlobalP[hand] - rig.restGlobalP[lower]).length
        val u0 = (rig.restGlobalP[lower] - rig.restGlobalP[upper]).normalized()
        val l0 = (rig.restGlobalP[hand] - rig.restGlobalP[lower]).normalized()
        val h0 = if (s == "l") -Vec3.Y else Vec3.Y
        val f0 = (rig.restGlobalP[rig["middle_01_$s"]] - rig.restGlobalP[hand]).normalized()
        /** Finger chains (3 joints each, thumb last). */
        val fingers = listOf("index", "middle", "ring", "pinky", "thumb").map { f -> (1..3).map { rig["${f}_0${it}_$s"] } }
        val fingerAxes = fingers.map { chain ->
            chain.map { j ->
                val c = rig.order.first { rig.parent[it] == j }
                val dir = (rig.restGlobalP[c] - rig.restGlobalP[j]).normalized()
                (dir cross -Vec3.Y).normalized()
            }
        }
    }

    private val legs = listOf(LegJ("l"), LegJ("r"))
    private val arms = listOf(ArmJ("l"), ArmJ("r"))

    // Measurements authors can rely on.
    val thighLength = legs[0].a
    val shinLength = legs[0].b
    val upperArmLength = arms[0].a
    val forearmLength = arms[0].b
    val ankleHeight = rig.restGlobalP[legs[0].foot].y
    /** Ball of the foot relative to the ankle joint in the rest (flat) foot. */
    val ballOffset = rig.restGlobalP[legs[0].ball] - rig.restGlobalP[legs[0].foot]
    val restPelvis = rig.restGlobalP[pelvis]

    fun solve(p: Pose): SkeletonPose {
        val n = rig.size
        val D = Array(n) { Quat.IDENTITY }
        val X = Array(n) { Vec3.ZERO }
        val direct = arrayOfNulls<Quat>(n)
        val anat = arrayOfNulls<Quat>(n)
        val ikErr = DoubleArray(4)

        // Trunk
        val trunk = Quat.ry(p.yaw) * Quat.rx(p.pitch) * Quat.rz(p.roll)
        direct[pelvis] = trunk
        for (k in 0..2) anat[spine[k]] = bend(p.spineFlex * spineShare[k], p.spineSide * spineShare[k], p.spineTwist * spineShare[k])
        anat[neck] = bend(p.neckFlex, p.neckSide, p.neckTwist)
        anat[head] = bend(p.headFlex, p.headSide, p.headTwist)

        // Clavicles + FK arm/finger parts that don't depend on positions.
        arms.forEachIndexed { i, j ->
            val ap = if (i == 0) p.armL else p.armR
            anat[j.clav] = side(i, Quat.rz(ap.clavElev) * Quat.ry(-ap.clavProt))
            j.fingers.forEachIndexed { f, chain ->
                val thumb = f == 4
                val amount = if (thumb) ap.thumb else ap.curl
                val maxDeg = if (thumb) doubleArrayOf(25.0, 35.0, 40.0) else doubleArrayOf(70.0, 95.0, 60.0)
                chain.forEachIndexed { k, jj -> anat[jj] = Quat.axisAngle(j.fingerAxes[f][k], Math.toRadians(maxDeg[k] * amount)) }
            }
        }

        for (j in rig.order) {
            val par = rig.parent[j]
            if (par < 0) { D[j] = Quat.IDENTITY; X[j] = rig.restGlobalP[j]; continue }
            X[j] = if (j == pelvis) p.pelvis else X[par] + D[par].rotate(rig.restGlobalP[j] - rig.restGlobalP[par])
            // Limbs are solved when their root joint is reached (its position is known by then).
            legs.forEachIndexed { i, lj -> if (j == lj.thigh) solveLeg(i, lj, if (i == 0) p.legL else p.legR, D[par], X[j], direct, ikErr) }
            arms.forEachIndexed { i, aj -> if (j == aj.upper) solveArm(i, aj, if (i == 0) p.armL else p.armR, X[j], direct, anat, ikErr) }
            D[j] = (direct[j] ?: (D[par] * (anat[j] ?: Quat.IDENTITY))).normalized()
            // Hands set in world space need the forearm's final rotation for twist sharing.
            arms.forEachIndexed { i, aj -> if (j == aj.lower) shareTwist(aj, if (i == 0) p.armL else p.armR, D, direct) }
        }

        // Local transforms for the renderer.
        val local = Array(n) { Quat.IDENTITY }
        val trans = Array(n) { rig.restT[it] }
        for (j in 0 until n) {
            val par = rig.parent[j]
            val g = D[j] * rig.restGlobalR[j]
            local[j] = if (par < 0) g.normalized() else ((D[par] * rig.restGlobalR[par]).conj() * g).normalized()
        }
        val pp = rig.parent[pelvis]
        trans[pelvis] = (D[pp] * rig.restGlobalR[pp]).conj().rotate(X[pelvis] - X[pp]).let { v ->
            val s = rig.restS[pp]; Vec3(v.x / s.x, v.y / s.y, v.z / s.z)
        }
        return SkeletonPose(local, trans, X, D, ikErr)
    }

    /** Anatomical bend: flex forward (+X axis tips +Y toward +Z), side bend to the left, twist to the left. */
    private fun bend(flex: Double, sideDeg: Double, twist: Double) = Quat.rx(flex) * Quat.rz(-sideDeg) * Quat.ry(twist)

    /** Rotations are authored for the left side; the right side uses the mirror image. */
    private fun side(i: Int, q: Quat) = if (i == 0) q else q.mirrored()

    private fun solveLeg(i: Int, j: LegJ, lp: LegPose, parentD: Quat, hip: Vec3, direct: Array<Quat?>, err: DoubleArray) {
        val dThigh: Quat
        val dCalf: Quat
        if (!lp.ik) {
            dThigh = parentD * side(i, Quat.rx(-lp.hipFlex) * Quat.rz(lp.hipAbd) * Quat.ry(lp.hipRot))
            dCalf = dThigh * Quat.rx(lp.knee)
        } else {
            val (u, l, h, e) = twoBone(hip, lp.ankle, j.a, j.b, lp.kneeDir)
            err[i] = e
            // Knee hinge axis at rest is +X for both legs.
            dThigh = Quat.fromFrames(j.u0, Vec3.X, u, h)
            dCalf = Quat.fromFrames(j.l0, Vec3.X, l, h)
        }
        direct[j.thigh] = dThigh
        direct[j.calf] = dCalf
        val dFoot = if (lp.footWorld) Quat.ry(lp.footYaw) * Quat.rx(lp.footPitch) * Quat.rz(-lp.footRoll)
        else dCalf * Quat.rx(lp.footPitch)
        direct[j.foot] = dFoot
        direct[j.ball] = dFoot * Quat.rx(-lp.toes)
    }

    private fun solveArm(i: Int, j: ArmJ, ap: ArmPose, shoulder: Vec3, direct: Array<Quat?>, anat: Array<Quat?>, err: DoubleArray) {
        if (!ap.ik) {
            // From the T-pose: hang the arm (palm in), then flex/abduct/rotate in body axes.
            val neutral = Quat.rz(-90.0)
            anat[j.upper] = side(i, Quat.rz(ap.abd) * Quat.rx(-ap.flex) * Quat.ry(ap.rot) * neutral)
            anat[j.lower] = side(i, Quat.ry(-ap.elbow) * Quat.rx(ap.pronation * 0.5))
            if (!ap.handWorld) anat[j.hand] = side(i, Quat.rx(ap.pronation * 0.5) * Quat.rz(-ap.wristFlex))
        } else {
            val (u, l, h, e) = twoBone(shoulder, ap.wrist, j.a, j.b, ap.elbowDir)
            err[2 + i] = e
            direct[j.upper] = Quat.fromFrames(j.u0, j.h0, u, h)
            direct[j.lower] = Quat.fromFrames(j.l0, j.h0, l, h)
            if (!ap.handWorld) anat[j.hand] = side(i, Quat.rx(ap.pronation) * Quat.rz(-ap.wristFlex))
        }
        if (ap.handWorld) direct[j.hand] = Quat.fromFrames(j.f0, -Vec3.Y, ap.fingerDir, ap.palmDir)
    }

    /** With a world-space hand, give half of the forearm twist to the forearm so the wrist doesn't candy-wrap. */
    private fun shareTwist(j: ArmJ, ap: ArmPose, D: Array<Quat>, direct: Array<Quat?>) {
        if (!ap.handWorld) return
        val hand = direct[j.hand] ?: return
        val rel = D[j.lower].conj() * hand
        val tw = twistAngle(rel, j.l0)
        D[j.lower] = (D[j.lower] * Quat.axisAngle(j.l0, tw * 0.5)).normalized()
    }

    private data class Limb(val u: Vec3, val l: Vec3, val h: Vec3, val err: Double)

    /** Two-bone IK. [pole] is the direction the middle joint (knee/elbow) should point. */
    private fun twoBone(root: Vec3, target: Vec3, a: Double, b: Double, pole: Vec3): Limb {
        val toT = target - root
        val dist = toT.length
        val dir = if (dist < 1e-9) -Vec3.Y else toT * (1 / dist)
        val d = dist.coerceIn(kotlin.math.abs(a - b) + 1e-4, a + b - 1e-5)
        val cosA = ((a * a + d * d - b * b) / (2 * a * d)).coerceIn(-1.0, 1.0)
        val sinA = sqrt(max(0.0, 1 - cosA * cosA))
        var pp = pole - dir * (pole dot dir)
        if (pp.length < 1e-6) pp = (if (kotlin.math.abs(dir.y) < 0.9) Vec3.Y else Vec3.Z).let { it - dir * (it dot dir) }
        pp = pp.normalized()
        val knee = root + (dir * cosA + pp * sinA) * a
        val end = root + dir * d
        val u = (knee - root).normalized()
        val l = (end - knee).normalized()
        val h = (pp cross u).normalized()
        return Limb(u, l, h, max(0.0, dist - min(dist, a + b)) + max(0.0, (kotlin.math.abs(a - b) - dist)))
    }
}
