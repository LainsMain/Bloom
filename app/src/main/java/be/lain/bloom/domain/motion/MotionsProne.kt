package be.lain.bloom.domain.motion

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** On all fours, planks and face-down work: the head points towards +Z. */
internal fun MotionLibrary.proneMotions(): List<Motion> {
    val list = mutableListOf<Motion>()
    val side = CameraSpec(72.0, 14.0)
    val kneeH = 0.055

    /** Kneeling: the knee rests at (x, kneeZ), the shin lies flat behind it; [hip] aims the knee exactly there. */
    fun shin(x: Double, kneeZ: Double, hip: Vec3): LegPose {
        val knee = Vec3(x, kneeH, kneeZ)
        val dy = 0.075 - kneeH
        val ankle = Vec3(x, 0.075, kneeZ - kotlin.math.sqrt(shinLength() * shinLength() - dy * dy))
        return LegPose(ik = true, ankle = ankle, kneeDir = (knee - (hip + ankle) * 0.5).normalized(), footPitch = 62.0)
    }

    val hipX = rig.rest("thigh_l").x

    /** On all fours: knees under the hips, hands under the shoulders. */
    fun allFours(pitch: Double = 86.0, spineFlex: Double = 0.0, neckFlex: Double = -6.0): Pose {
        val hip = Vec3(hipX, kneeH + solver.thighLength, 0.0)
        return Pose(pelvis = Vec3.ZERO, pitch = pitch, spineFlex = spineFlex, neckFlex = neckFlex).symLegs(shin(0.11, 0.0, hip)).anchor("thigh_l", hip)
    }

    fun handsUnder(p: Pose, wide: Double = 0.0, forward: Double = 0.02): ArmPose {
        val sh = p.at("upperarm_l")
        return palm(sh.x + wide, sh.z + forward, Vec3(0.3, 0.2, -1.0))
    }

    val quad = allFours().let { it.symArms(handsUnder(it)) }
    val mats = listOf(mat(0.1))

    // Cat-cow: round the back up (chin in), then let the belly drop and look up.
    run {
        val hands = quad.armL
        val hip = quad.at("thigh_l")
        // Cat: tuck the tailbone and round the back up; cow: tip the pelvis, belly down, look up.
        val cat = quad.copy(pitch = 74.0, spineFlex = 34.0, neckFlex = 16.0, headFlex = 6.0).anchor("thigh_l", hip)
        val cow = quad.copy(pitch = 100.0, spineFlex = -28.0, neckFlex = -24.0, headFlex = -8.0).anchor("thigh_l", hip)
        list += motion("cat-cow", side, mats, "start" to quad, "cat" to cat.symArms(hands), "cow" to cow.symArms(hands))
    }

    // Donkey kick (left leg): knee stays bent at 90°, press the sole to the ceiling.
    run {
        val hip = quad.at("thigh_l")
        val kick = LegPose(ik = true, ankle = hip + Vec3(0.0, 0.43, -0.40), kneeDir = Vec3(0.0, -1.0, -1.0), footPitch = 0.0)
        list += motion("donkey-kick", side, mats, "start" to quad, "top" to quad.copy(legL = kick, roll = -2.0))
    }

    // Fire hydrant (left leg): lift the bent knee out to the side, hips square.
    run {
        val hip = quad.at("thigh_l")
        val knee = hip + Vec3(0.34, -0.05, -0.04)
        val lift = LegPose(ik = true, ankle = knee + Vec3(0.03, 0.04, -0.44), kneeDir = Vec3(1.0, -0.3, 0.2), footPitch = 45.0)
        list += motion("fire-hydrant", CameraSpec(180.0, 24.0), mats, "start" to quad, "top" to quad.copy(legL = lift))
    }

    // Bird dog: right arm forward and left leg back, long and level, then switch.
    run {
        val sh = quad.at("upperarm_r")
        val hip = quad.at("thigh_l")
        val arm = ArmPose(ik = true, wrist = sh + Vec3(0.0, 0.02, 0.48), elbowDir = Vec3(0.0, -1.0, 0.0), handWorld = true,
            fingerDir = Vec3.Z, palmDir = Vec3(1.0, -0.2, 0.0), curl = 0.15, thumb = 0.2)
        val leg = LegPose(ik = true, ankle = hip + Vec3(0.0, 0.02, -(legLength - 0.02)), kneeDir = Vec3(0.0, -1.0, 0.0), footPitch = 15.0)
        list += motion("bird-dog", side, mats, "start" to quad, "reach" to quad.copy(armR = arm, legL = leg))
    }

    /** A straight line from the knees (or toes) to the shoulders, resting on the forearms or hands. */
    fun plankBody(onKnees: Boolean, shoulderH: Double, feetX: Double = 0.10): Pose {
        val base = Pose(pelvis = Vec3.ZERO, pitch = 80.0)
        return if (onKnees) {
            val len = solver.thighLength + 0.49
            val beta = Math.toDegrees(asin(((shoulderH - kneeH) / len).coerceIn(-1.0, 1.0)))
            val p = base.copy(pitch = 90.0 - beta)
            val kneeZ = 0.0
            val hip = Vec3(hipX, kneeH + solver.thighLength * sin(Math.toRadians(beta)), kneeZ + solver.thighLength * cos(Math.toRadians(beta)))
            p.symLegs(shin(feetX, kneeZ, hip)).anchor("thigh_l", hip)
        } else {
            val leg0 = onBall(feetX, -0.62, 70.0)
            val len = legLength + 0.49
            val beta = Math.toDegrees(asin(((shoulderH - leg0.ankle.y) / len).coerceIn(-1.0, 1.0)))
            val legL = onBall(feetX, -0.62, 72.0 - beta * 0.6)
            val dir = Vec3(0.0, sin(Math.toRadians(beta)), cos(Math.toRadians(beta)))
            val hip = legL.ankle + dir * (legLength - 0.004)
            base.copy(pitch = 90.0 - beta).symLegs(legL).anchor("thigh_l", Vec3(rig.rest("thigh_l").x, hip.y, hip.z))
        }
    }

    fun forearms(p: Pose): ArmPose {
        val sh = p.at("upperarm_l")
        return ArmPose(ik = true, wrist = Vec3(sh.x - 0.02, 0.03, sh.z + 0.22), elbowDir = Vec3(0.2, -1.0, -0.4), handWorld = true,
            fingerDir = Vec3(-0.2, 0.0, 1.0), palmDir = -Vec3.Y, curl = 0.12, thumb = -0.4)
    }

    // Knee plank and forearm plank (holds).
    run {
        val k = plankBody(true, 0.30).copy(neckFlex = -4.0)
        list += motion("knee-plank", side, mats, "start" to k.symArms(forearms(k)))
        val p = plankBody(false, 0.30).copy(neckFlex = -4.0)
        list += motion("plank", side, mats, "start" to p.symArms(forearms(p)))
    }

    // Plank shoulder taps: high plank, feet wide, tap the opposite shoulder without rocking.
    run {
        val p = plankBody(false, 0.52, feetX = 0.18).copy(neckFlex = -6.0)
        val arm = handsUnder(p, 0.03, 0.0)
        val start = p.symArms(arm)
        val target = start.at("clavicle_l").lerp(start.at("upperarm_l"), 0.6)
        val tap = ArmPose(ik = true, wrist = target + Vec3(-0.04, -0.06, -0.05), elbowDir = Vec3(-0.3, -1.0, 0.2), handWorld = true,
            fingerDir = Vec3(1.0, 0.2, 0.2), palmDir = Vec3(0.2, -1.0, 0.0), curl = 0.2, thumb = 0.2)
        list += motion("plank-shoulder-tap", CameraSpec(28.0, 20.0), mats, "start" to start, "tap" to start.copy(armR = tap, roll = 2.0))
    }

    // Knee push-up: straight line from knees to head, chest to the floor.
    run {
        val top = plankBody(true, 0.52).copy(neckFlex = -8.0)
        val bottom = plankBody(true, 0.23).copy(neckFlex = -4.0)
        val sh = bottom.at("upperarm_l")
        val arm = palm(sh.x + 0.08, sh.z - 0.03, Vec3(0.55, 0.15, -0.8))
        list += motion("knee-pushup", CameraSpec(62.0, 12.0), mats, "start" to top.symArms(arm), "bottom" to bottom.symArms(arm))
    }

    // Lying face down (Y-T-W, reverse snow angels).
    val proneLegs = LegPose(hipFlex = -2.0, hipAbd = 4.0, knee = 4.0, footPitch = 62.0)
    // The chest is deeper than the pelvis, so the trunk rests tilted slightly head-up.
    val lying = Pose(pelvis = Vec3(0.0, 0.118, 0.0), pitch = 80.0, neckFlex = 16.0).symLegs(proneLegs.copy(hipFlex = -10.0))
        .symArms(ArmPose(flex = 2.0, abd = 12.0, rot = 0.0, elbow = 6.0, curl = 0.3))
    val lifted = lying.copy(neckFlex = 4.0)
    run {
        // Each letter starts from arms resting out to the sides and sweeps along the floor, then lifts.
        val rest = lying.symArms(ArmPose(flex = 6.0, abd = 90.0, rot = 90.0, elbow = 6.0, curl = 0.4, thumb = 0.0))
        val y = lifted.symArms(ArmPose(flex = -14.0, abd = 145.0, rot = 90.0, elbow = 4.0, curl = 0.45, thumb = 0.0, clavProt = -8.0))
        val t = lifted.symArms(ArmPose(flex = -14.0, abd = 90.0, rot = 90.0, elbow = 4.0, curl = 0.45, thumb = 0.0, clavProt = -10.0))
        val w = lifted.symArms(ArmPose(flex = -18.0, abd = 55.0, rot = 70.0, elbow = 105.0, curl = 0.45, thumb = 0.0, clavProt = -12.0))
        list += motion("prone-ytw", CameraSpec(25.0, 32.0), listOf(mat(-0.15)), "start" to rest, "y" to y, "t" to t, "w" to w)
    }
    run {
        val low = lifted.symArms(ArmPose(flex = -14.0, abd = 12.0, rot = 0.0, elbow = 4.0, curl = 0.3, clavProt = -8.0))
        val top = lifted.symArms(ArmPose(flex = -10.0, abd = 168.0, rot = 0.0, elbow = 6.0, curl = 0.3, clavProt = -8.0))
        list += motion("reverse-snow-angel", CameraSpec(25.0, 32.0), listOf(mat(-0.15)), "start" to low, "top" to top)
    }

    // Child's pose: knees wide, sit back to the heels, arms long, forehead down.
    run {
        val kneeX = 0.24
        val legs = LegPose(ik = true, ankle = Vec3(0.06, 0.075, -0.42), kneeDir = Vec3(0.7, -1.0, 0.7), footPitch = 62.0)
        var p = Pose(pelvis = Vec3(0.0, 0.30, -0.27), pitch = 97.0, spineFlex = 9.0, neckFlex = 0.0).symLegs(legs)
        val sh = p.at("upperarm_l")
        val arm = palm(sh.x + 0.06, sh.z + 0.42, Vec3(0.2, 1.0, 0.0))
        p = p.symArms(arm)
        @Suppress("UNUSED_VARIABLE") val unused = kneeX
        list += motion("childs-pose", side, mats, "start" to p)
    }

    // Stomach vacuum: stand tall, hands on hips, breathe out and draw the belly in.
    list += motion("stomach-vacuum", CameraSpec(40.0, 6.0), emptyList(), "start" to stand(0.12, 6.0).symArms(handsOnHips))

    return list
}

private fun MotionLibrary.shinLength() = solver.shinLength
