package be.lain.bloom.domain.motion

import kotlin.math.cos
import kotlin.math.sin

private fun toeDir(yawDeg: Double) = Vec3(sin(Math.toRadians(yawDeg)), 0.0, cos(Math.toRadians(yawDeg)))

/** Standing lower-body and hinge exercises (the figure faces +Z, the left leg is the working leg). */
internal fun MotionLibrary.standingMotions(): List<Motion> {
    val list = mutableListOf<Motion>()

    // Chair squat: sit back until the glutes touch the seat, stand tall.
    run {
        val toe = 10.0
        val start = stand().symLegs(flat(0.15, -0.04, toe, toeDir(toe + 4)))
        val bottom = start.copy(pelvis = Vec3(0.0, 0.565, -0.27), pitch = 36.0, spineFlex = -5.0, neckFlex = -15.0, headFlex = -8.0)
            .symArms(armsForward(108.0))
        list += motion("box-squat", CameraSpec(48.0, 8.0), listOf(box(0.0, -0.45, 0.44, 0.42, 0.45)), "start" to start, "bottom" to bottom)
    }

    // Sumo squat: wide stance, toes out, sink straight down with the chest up.
    run {
        val toe = 38.0
        val clasp = ArmPose(flex = 32.0, abd = -16.0, rot = -40.0, elbow = 98.0, curl = 0.5, thumb = 0.3)
        val start = stand().symLegs(flat(0.31, -0.03, toe, toeDir(toe))).symArms(clasp).copy(pelvis = restPelvis - Vec3(0.0, 0.05, 0.0))
        val bottom = start.copy(pelvis = Vec3(0.0, 0.56, -0.10), pitch = 14.0, neckFlex = -6.0)
        list += motion("sumo-squat", CameraSpec(28.0, 8.0), emptyList(), "start" to start, "bottom" to bottom)
    }

    // Split squat (left leg in front): drop the back knee straight down.
    run {
        val front = flat(0.12, 0.30, 4.0, Vec3(0.05, 0.0, 1.0))
        val back = onBall(-0.12, -0.50, 52.0, 0.0, Vec3(0.0, -0.4, 1.0))
        val base = Pose(pelvis = Vec3(0.0, 0.84, -0.08), legL = front, legR = back).symArms(handsOnHips)
        val bottom = base.copy(pelvis = Vec3(0.0, 0.50, -0.12), pitch = 6.0)
        list += motion("split-squat", CameraSpec(78.0, 8.0), emptyList(), "start" to base, "bottom" to bottom.copy(legR = onBall(-0.12, -0.50, 62.0, 0.0, Vec3(0.0, -0.6, 1.0))))
    }

    // Bulgarian split squat: back foot on a couch, lean slightly forward, slow lowering.
    run {
        val bench = box(-0.10, -0.80, 0.5, 0.42, 0.44)
        val front = flat(0.12, 0.24, 4.0, Vec3(0.05, 0.0, 1.0))
        val backFoot = LegPose(ik = true, ankle = Vec3(-0.12, 0.505, -0.66), kneeDir = Vec3(0.0, -0.5, 1.0), footPitch = 55.0)
        val start = Pose(pelvis = Vec3(0.0, 0.86, -0.12), pitch = 8.0, legL = front, legR = backFoot).symArms(handsOnHips)
        val bottom = start.copy(pelvis = Vec3(0.0, 0.52, -0.16), pitch = 20.0, spineFlex = 2.0)
        list += motion("bulgarian-split-squat", CameraSpec(78.0, 8.0), listOf(bench), "start" to start, "bottom" to bottom)
    }

    // Step-up (left foot on the step): drive up, bring the other knee up, step down slowly.
    run {
        val step = box(0.0, 0.30, 0.55, 0.36, 0.20)
        val up = flat(0.11, 0.28, 0.0).copy(ankle = Vec3(0.11, ankleH + 0.20, 0.28))
        val floor = flat(-0.11, -0.10, 0.0)
        val start = Pose(pelvis = Vec3(0.0, 0.83, -0.04), pitch = 14.0, neckFlex = -8.0, legL = up, legR = floor).symArms(relaxedArm)
        val knee = LegPose(ik = true, ankle = Vec3(-0.11, 0.52, 0.08), kneeDir = Vec3(0.0, 0.3, 1.0), footPitch = 25.0)
        val top = Pose(pelvis = Vec3(0.0, restPelvis.y + 0.19, 0.20), pitch = 2.0, legL = up, legR = knee)
            .copy(armL = ArmPose(flex = -25.0, abd = 8.0, elbow = 70.0, curl = 0.5), armR = ArmPose(flex = 45.0, abd = 6.0, elbow = 80.0, curl = 0.5))
        list += motion("step-up", CameraSpec(55.0, 10.0), listOf(step), "start" to start, "top" to top)
    }

    // Lunges alternate: "bottom" is authored with the LEFT foot doing the stepping.
    fun lunge(id: String, stepTo: Vec3, stepMid: Vec3, bottomPelvis: Vec3, heel: Double, yaw: Double, pitch: Double, roll: Double = 0.0, armsOut: Boolean = false, camera: CameraSpec) {
        val start = stand(0.11, 6.0)
        val stance = start.legR
        val arms = if (armsOut) armsForward(85.0) else handsOnHips
        val startArms = if (armsOut) relaxedArm else handsOnHips
        // Every keyframe of the stepping foot is world-oriented so it never snaps mid-step.
        val stepPose = start.copy(pelvis = start.pelvis + Vec3(bottomPelvis.x * 0.35, -0.07, bottomPelvis.z * 0.3), pitch = pitch * 0.4,
            legL = LegPose(ik = true, ankle = stepMid, kneeDir = Vec3(0.0, 0.2, 1.0), footWorld = true, footPitch = 12.0, footYaw = yaw * 0.5), legR = stance)
            .symArms(if (armsOut) armsForward(45.0, 30.0) else handsOnHips)
        val stepLeg = if (heel > 0) onBall(stepTo.x, stepTo.z, heel, yaw, Vec3(0.2 * kotlin.math.sign(stepTo.x), -0.3, 1.0))
        else flat(stepTo.x, stepTo.z, yaw, toeDir(yaw))
        val bottom = start.copy(pelvis = bottomPelvis, pitch = pitch, roll = roll, legL = stepLeg, legR = stance.copy(kneeDir = Vec3(-0.15, 0.0, 1.0))).symArms(arms)
        list += motion(id, camera, emptyList(), "start" to start.symArms(startArms), "step" to stepPose, "bottom" to bottom)
    }
    lunge("reverse-lunge", Vec3(0.11, 0.0, -0.80), Vec3(0.11, 0.20, -0.42), Vec3(0.0, 0.53, -0.40), heel = 58.0, yaw = 0.0, pitch = 8.0, camera = CameraSpec(72.0, 8.0))
    lunge("curtsy-lunge", Vec3(-0.30, 0.0, -0.58), Vec3(-0.05, 0.20, -0.32), Vec3(-0.06, 0.55, -0.28), heel = 58.0, yaw = -10.0, pitch = 10.0, roll = -3.0, camera = CameraSpec(30.0, 10.0))
    lunge("lateral-lunge", Vec3(0.62, 0.0, -0.05), Vec3(0.36, 0.18, -0.06), Vec3(0.34, 0.60, -0.20), heel = 0.0, yaw = 18.0, pitch = 32.0, armsOut = true, camera = CameraSpec(8.0, 10.0))

    // Good morning: hands behind head, hinge from the hips with a flat back.
    run {
        val start = stand(0.13, 6.0, drop = 0.02).symArms(handsBehindHead)
        val bottom = start.copy(pelvis = Vec3(0.0, 0.875, -0.20), pitch = 72.0, neckFlex = -18.0, headFlex = -6.0)
        list += motion("good-morning", CameraSpec(82.0, 6.0), emptyList(), "start" to start, "bottom" to bottom)
    }

    // Single-leg Romanian deadlift (standing on the left leg): the free leg rises in line with the back.
    run {
        val standLeg = flat(0.09, -0.05, 4.0)
        val free = LegPose(hipFlex = -8.0, knee = 35.0, footPitch = 20.0)
        val start = Pose(pelvis = Vec3(0.06, 0.915, -0.05), roll = 2.0, legL = standLeg, legR = free).symArms(relaxedArm)
        val bottom = start.copy(pelvis = Vec3(0.06, 0.86, -0.22), pitch = 78.0, neckFlex = -20.0, headFlex = -5.0,
            legR = LegPose(hipFlex = 4.0, hipAbd = 2.0, knee = 6.0, footPitch = -10.0))
            .symArms(ArmPose(flex = 82.0, abd = 4.0, elbow = 6.0, curl = 0.35))
        list += motion("single-leg-rdl", CameraSpec(80.0, 8.0), emptyList(), "start" to start, "bottom" to bottom)
    }

    return list
}
