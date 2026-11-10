package be.lain.bloom.domain.motion

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Lying on one side. Clamshell and leg raise work the top (left) leg; side planks rest on the left forearm. */
internal fun MotionLibrary.sideMotions(): List<Motion> {
    val list = mutableListOf<Motion>()
    val front = CameraSpec(8.0, 18.0)
    val matAcross = mat(0.0, -0.15, yaw = 90.0)

    // Lying on the right side (head towards −X, facing the camera), head resting on the right arm.
    fun onRightSide(legs: LegPose): Pose {
        val p = Pose(pelvis = Vec3(0.0, 0.0, 0.0), roll = 90.0, neckSide = 14.0, headSide = 6.0)
            .copy(legL = legs, legR = legs)
            .copy(armR = ArmPose(flex = 165.0, abd = 8.0, elbow = 25.0, curl = 0.35, rot = 10.0), armL = ArmPose(flex = 55.0, abd = 0.0, elbow = 60.0, curl = 0.3))
        val hip = p.at("thigh_r")
        return p.copy(pelvis = p.pelvis + Vec3(0.0, 0.092 - hip.y, 0.0))
    }

    // Clamshell: knees bent, feet together; open the top knee like a clam.
    run {
        val bent = LegPose(hipFlex = 48.0, knee = 85.0, footPitch = 10.0)
        val base = onRightSide(bent)
        val footR = base.at("foot_r")
        val top = LegPose(ik = true, ankle = footR + Vec3(0.0, 0.085, 0.0), kneeDir = Vec3(0.0, -0.15, 1.0), footPitch = 10.0)
        val open = top.copy(kneeDir = Vec3(0.0, 1.0, 0.55))
        list += motion("clamshell", front, listOf(matAcross), "start" to base.copy(legL = top), "top" to base.copy(legL = open))
    }

    // Side-lying leg raise: top leg long, toes forward, lifted slightly behind the body.
    run {
        val bottom = LegPose(hipFlex = 18.0, knee = 32.0, footPitch = 10.0)
        val base = onRightSide(bottom)
        val down = LegPose(hipAbd = 3.0, hipFlex = 2.0, knee = 2.0, footPitch = 5.0)
        val up = LegPose(hipAbd = 42.0, hipFlex = -8.0, knee = 2.0, footPitch = 5.0)
        list += motion("side-lying-leg-raise", front, listOf(matAcross), "start" to base.copy(legL = down), "top" to base.copy(legL = up))
    }

    // Side planks on the left forearm: straight line from the head to the knees (or feet).
    fun sidePlank(id: String, onKnees: Boolean) {
        val legs = if (onKnees) LegPose(hipFlex = 4.0, knee = 92.0, footPitch = 30.0) else LegPose(hipFlex = 2.0, knee = 2.0, footPitch = 10.0)
        val legsTop = if (onKnees) legs else legs.copy(hipFlex = 6.0)
        val shoulderH = 0.30
        val baseLen = if (onKnees) solver.thighLength + 0.49 else legLength + 0.49
        val lowH = if (onKnees) 0.06 else 0.07
        val beta = Math.toDegrees(kotlin.math.asin(((shoulderH - lowH) / baseLen).coerceIn(-1.0, 1.0)))
        var p = Pose(pelvis = Vec3.ZERO, roll = -(90.0 - beta), neckSide = -(90.0 - beta) * 0.12).copy(legL = legs, legR = legsTop)
            .copy(armR = ArmPose(flex = -6.0, abd = 38.0, rot = -88.0, elbow = 96.0, curl = 0.2, wristFlex = 10.0))
        val pivot = if (onKnees) "calf_l" else "foot_l"
        p = p.anchor(pivot, Vec3(p.at(pivot).x, lowH, 0.0))
        val sh = p.at("upperarm_l")
        val forearm = ArmPose(ik = true, wrist = Vec3(sh.x + 0.02, 0.03, sh.z + 0.24), elbowDir = Vec3(0.3, -1.0, -0.2), handWorld = true,
            fingerDir = Vec3(-0.2, 0.0, 1.0), palmDir = -Vec3.Y, curl = 0.15, thumb = -0.3)
        list += motion(id, CameraSpec(4.0, 12.0), listOf(mat(0.0, 0.35, yaw = 90.0)), "start" to p.copy(armL = forearm))
    }
    sidePlank("side-plank-knees", true)
    sidePlank("side-plank", false)

    return list
}

/** Exercises that use furniture: wall, counter, chair, doorframe, table. */
internal fun MotionLibrary.propMotions(): List<Motion> {
    val list = mutableListOf<Motion>()

    /** Straight body pivoting at the ankles (feet flat at z = 0), leaning [lean]° from vertical (forward +). */
    fun leaning(lean: Double, feetX: Double = 0.12): Pose {
        val feet = flat(feetX, 0.0, 6.0)
        val shin = solver.shinLength
        val dir = Vec3(0.0, cos(Math.toRadians(lean)), sin(Math.toRadians(lean)))
        val hip = feet.ankle + dir * (legLength - 0.004)
        return Pose(pelvis = Vec3.ZERO, pitch = lean).symLegs(feet).anchor("thigh_l", Vec3(rig.rest("thigh_l").x, hip.y, hip.z))
            .also { @Suppress("UNUSED_VARIABLE") val unused = shin }
    }

    // Wall push-up: hands on the wall at shoulder height, chest towards the wall.
    run {
        val top = leaning(17.0)
        val sh = top.at("upperarm_l")
        val wallZ = sh.z + 0.47
        val hand = ArmPose(ik = true, wrist = Vec3(sh.x + 0.05, sh.y - 0.06, wallZ - 0.03), elbowDir = Vec3(0.6, -0.6, -0.5), handWorld = true,
            fingerDir = Vec3(0.15, 1.0, 0.0), palmDir = Vec3.Z, curl = 0.08, thumb = -0.3)
        val bottom = leaning(29.0).copy(neckFlex = -6.0)
        val wall = Prop(PropKind.BOX, Vec3(0.0, 0.9, wallZ + 0.04), Vec3(1.0, 1.8, 0.08))
        list += motion("wall-pushup", CameraSpec(100.0, 8.0), listOf(wall), "start" to top.symArms(hand), "bottom" to bottom.symArms(hand))
    }

    // Incline push-up on a kitchen counter.
    run {
        fun body(angle: Double): Pose = leaning(90.0 - angle).copy(neckFlex = -6.0)
        val top = body(56.0)
        val counterTop = 0.90
        val sh = top.at("upperarm_l")
        val edge = sh.z + 0.40
        val hand = palm(sh.x + 0.07, edge - 0.06, Vec3(0.6, 0.1, -0.8)).copy(wrist = Vec3(sh.x + 0.07, counterTop + wristH, edge - 0.06))
        val counter = box(0.0, edge + 0.30, 1.2, 0.6, counterTop)
        list += motion("incline-pushup", CameraSpec(68.0, 8.0), listOf(counter), "start" to top.symArms(hand), "bottom" to body(43.0).symArms(hand))
    }

    // Chair dip: hands on the seat edge behind, elbows straight back.
    run {
        val seat = 0.46
        val chair = box(0.0, -0.27, 0.46, 0.42, seat)
        val hand = ArmPose(ik = true, wrist = Vec3(0.19, seat + wristH, -0.09), elbowDir = Vec3(0.1, 0.0, -1.0), handWorld = true,
            fingerDir = Vec3.Z, palmDir = -Vec3.Y, curl = 0.45, thumb = 0.2)
        val feet = flat(0.12, 0.52, 4.0, Vec3(0.05, 0.3, 1.0))
        val start = Pose(pelvis = Vec3(0.0, 0.475, 0.03), pitch = 4.0, neckFlex = -4.0).symLegs(feet).symArms(hand)
        val bottom = start.copy(pelvis = Vec3(0.0, 0.28, 0.05), pitch = 8.0)
        list += motion("bench-dip", CameraSpec(66.0, 8.0), listOf(chair), "start" to start, "bottom" to bottom)
    }

    // Doorframe row: hold both sides of the frame, lean back, pull the chest to the frame.
    run {
        val frameZ = 0.42
        val posts = listOf(
            Prop(PropKind.BOX, Vec3(0.43, 1.02, frameZ), Vec3(0.08, 2.04, 0.12)),
            Prop(PropKind.BOX, Vec3(-0.43, 1.02, frameZ), Vec3(0.08, 2.04, 0.12)),
            Prop(PropKind.BOX, Vec3(0.0, 2.08, frameZ), Vec3(0.94, 0.08, 0.12)),
        )
        val grip = ArmPose(ik = true, wrist = Vec3(0.37, 1.12, frameZ - 0.02), elbowDir = Vec3(0.4, -1.0, -0.3), handWorld = true,
            fingerDir = Vec3(0.2, 0.0, 1.0), palmDir = Vec3(1.0, 0.0, 0.0), curl = 0.7, thumb = 0.5)
        fun lean(deg: Double): Pose {
            val feet = flat(0.11, 0.30, 4.0)
            val dir = Vec3(0.0, cos(Math.toRadians(deg)), -sin(Math.toRadians(deg)))
            val hip = feet.ankle + dir * (legLength - 0.004)
            return Pose(pelvis = Vec3.ZERO, pitch = -deg, neckFlex = 6.0).symLegs(feet).anchor("thigh_l", Vec3(rig.rest("thigh_l").x, hip.y, hip.z))
        }
        list += motion("doorway-row", CameraSpec(108.0, 10.0), posts, "start" to lean(11.0).symArms(grip), "top" to lean(3.0).symArms(grip))
    }

    // Table row: under a sturdy table, knees bent, pull the chest up to the edge.
    run {
        val topY = 0.74
        val edgeZ = 0.18
        val table = listOf(Prop(PropKind.BOX, Vec3(0.0, topY - 0.02, edgeZ - 0.5), Vec3(0.86, 0.04, 1.0))) +
            listOf(0.39, -0.39).flatMap { x -> listOf(edgeZ - 0.04, edgeZ - 0.96).map { z -> Prop(PropKind.BOX, Vec3(x, (topY - 0.04) / 2, z), Vec3(0.05, topY - 0.04, 0.05)) } }
        val grip = ArmPose(ik = true, wrist = Vec3(0.28, topY - 0.06, edgeZ - 0.02), elbowDir = Vec3(0.6, -1.0, 0.2), handWorld = true,
            fingerDir = Vec3(0.0, 1.0, -0.3), palmDir = Vec3.Z, curl = 0.75, thumb = 0.5)
        val feet = flat(0.13, 1.02, 4.0, Vec3(0.0, 1.0, 0.4))
        fun hang(shoulderY: Double): Pose {
            val shoulder = Vec3(0.0, shoulderY, edgeZ - 0.06)
            val hipY = 0.27 + (shoulderY - 0.23) * 0.75
            val hip = Vec3(0.0, hipY, shoulder.z + 0.48)
            val pitch = -90.0 - Math.toDegrees(atan2(hip.y - shoulder.y, hip.z - shoulder.z))
            val p = Pose(pelvis = Vec3.ZERO, pitch = pitch, neckFlex = 12.0).symLegs(feet)
            val cur = p.at("spine_03")
            return p.copy(pelvis = p.pelvis + (Vec3(0.0, shoulderY - 0.06, shoulder.z - 0.02) - cur))
        }
        list += motion("table-row", CameraSpec(70.0, 18.0), table, "start" to hang(0.34).symArms(grip), "top" to hang(0.48).symArms(grip))
    }

    return list
}

/** Warm-up loops and the kneeling hip-flexor stretch. */
internal fun MotionLibrary.warmupMotions(): List<Motion> {
    val list = mutableListOf<Motion>()
    val front = CameraSpec(28.0, 8.0)

    // March in place: knee to hip height, opposite arm swings forward.
    run {
        val start = stand()
        val knee = start.copy(pelvis = start.pelvis + Vec3(-0.01, 0.01, 0.0),
            legL = LegPose(ik = true, ankle = Vec3(0.11, 0.42, 0.20), kneeDir = Vec3(0.0, 0.3, 1.0), footWorld = true, footPitch = 30.0),
            armR = ArmPose(flex = 40.0, abd = 6.0, elbow = 85.0, curl = 0.5), armL = ArmPose(flex = -25.0, abd = 8.0, elbow = 60.0, curl = 0.5))
        list += motion("march-in-place", CameraSpec(50.0, 6.0), emptyList(), "start" to start, "knee" to knee)
    }

    // Step jacks: step out while the arms rise overhead, step back in.
    run {
        val start = stand(0.09, 6.0)
        val out = start.copy(pelvis = start.pelvis + Vec3(0.12, -0.03, 0.0), roll = -2.0, legL = flat(0.42, -0.07, 14.0))
            .symArms(ArmPose(abd = 158.0, flex = 8.0, elbow = 12.0, curl = 0.2, thumb = 0.1, clavElev = 12.0))
        val lift = start.copy(pelvis = start.pelvis + Vec3(0.06, -0.01, 0.0),
            legL = LegPose(ik = true, ankle = Vec3(0.26, 0.17, -0.07), kneeDir = Vec3(0.4, 0.0, 1.0), footWorld = true, footPitch = 15.0, footYaw = 8.0))
            .symArms(ArmPose(abd = 80.0, flex = 6.0, elbow = 14.0, curl = 0.25))
        list += motion("step-jacks", front, emptyList(), "start" to start, "lift" to lift, "out" to out)
    }

    // Hip circles: hands on hips, the pelvis draws a big slow circle.
    run {
        val base = stand(0.15, 8.0).symArms(handsOnHips)
        fun at(dx: Double, dz: Double, roll: Double, pitch: Double) =
            base.copy(pelvis = base.pelvis + Vec3(dx, -0.01, dz), roll = roll, spineSide = roll * 1.4, pitch = pitch, spineFlex = -pitch * 1.3)
        list += motion("hip-circles", front, emptyList(),
            "start" to at(0.0, 0.09, 0.0, -9.0), "left" to at(0.09, 0.0, -8.0, 0.0), "back" to at(0.0, -0.09, 0.0, 9.0), "right" to at(-0.09, 0.0, 8.0, 0.0))
    }

    // Arm circles: arms out to the sides drawing circles.
    run {
        val base = stand(0.13, 6.0)
        fun arms(abd: Double, flex: Double) = base.symArms(ArmPose(abd = abd, flex = flex, rot = 0.0, elbow = 4.0, curl = 0.15, thumb = 0.1, clavElev = (abd - 80) * 0.3))
        list += motion("arm-circles", front, emptyList(),
            "start" to arms(80.0, 0.0), "front" to arms(90.0, 14.0), "up" to arms(100.0, 0.0), "back" to arms(90.0, -14.0))
    }

    // Leg swings (left leg), holding a chair back with the right hand.
    run {
        val chair = box(-0.44, 0.0, 0.34, 0.34, 0.92)
        val base = Pose(pelvis = restPelvis + Vec3(-0.04, 0.0, 0.0), roll = 3.0, legR = flat(-0.10, -0.06, 4.0))
            .copy(armR = ArmPose(ik = true, wrist = Vec3(-0.38, 0.95, 0.02), elbowDir = Vec3(-0.3, -1.0, -0.3), handWorld = true,
                fingerDir = Vec3(-0.3, 0.0, 1.0), palmDir = -Vec3.Y, curl = 0.6, thumb = 0.4), armL = ArmPose(abd = 28.0, flex = 5.0, elbow = 20.0, curl = 0.3))
        val mid = base.copy(legL = LegPose(hipFlex = 6.0, hipAbd = 3.0, knee = 30.0, footPitch = 15.0))
        val fwd = base.copy(legL = LegPose(hipFlex = 58.0, hipAbd = 2.0, knee = 12.0, footPitch = 5.0), pitch = -3.0)
        val back = base.copy(legL = LegPose(hipFlex = -28.0, hipAbd = 3.0, knee = 28.0, footPitch = 25.0), pitch = 5.0)
        list += motion("leg-swings", CameraSpec(80.0, 6.0), listOf(chair), "start" to mid, "front" to fwd, "back" to back)
    }

    // Hip flexor stretch (left knee down): pelvis tucked, shift forward.
    run {
        val kneeH = 0.055
        val knee = Vec3(0.11, kneeH, -0.12)
        val hip = knee + Vec3(0.001, solver.thighLength * cos(Math.toRadians(16.0)), solver.thighLength * sin(Math.toRadians(16.0)))
        val dy = 0.075 - kneeH
        val ankle = Vec3(0.11, 0.075, knee.z - sqrt(solver.shinLength * solver.shinLength - dy * dy))
        val kneelLeg = LegPose(ik = true, ankle = ankle, kneeDir = (knee - (hip + ankle) * 0.5).normalized(), footPitch = 62.0)
        val frontLeg = flat(-0.13, 0.46, 6.0, Vec3(0.0, 0.5, 1.0))
        val p = Pose(pelvis = Vec3.ZERO, pitch = -6.0, legL = kneelLeg, legR = frontLeg).symArms(handsOnHips).anchor("thigh_l", hip)
        list += motion("hip-flexor-stretch", CameraSpec(78.0, 8.0), listOf(mat(0.15)), "start" to p)
    }

    return list
}
