package be.lain.bloom.domain.motion

/** Lying on the back: head towards −Z, feet towards +Z. */
internal fun MotionLibrary.supineMotions(): List<Motion> {
    val list = mutableListOf<Motion>()
    val side = CameraSpec(70.0, 16.0)
    val floorArm = palm(0.27, -0.07, Vec3(0.3, 1.0, 0.0), fingers = Vec3.Z)

    fun supine(feetZ: Double = 0.40, feetX: Double = 0.13): Pose {
        val leg = flat(feetX, feetZ, 0.0, Vec3(0.0, 1.0, 0.25))
        return Pose(pelvis = Vec3(0.0, 0.115, 0.0), pitch = -90.0, neckFlex = 8.0).symLegs(leg).symArms(floorArm)
    }

    /** Hips raised into a bridge with the shoulders and head staying where they were. */
    fun Pose.bridged(pitch: Double, from: Pose = this): Pose {
        val neck = from.at("neck_01")
        val lift = -90.0 - pitch
        return copy(pitch = pitch, neckFlex = 8.0 + lift * 0.75, headFlex = lift * 0.2, spineFlex = 3.0).anchor("neck_01", neck + Vec3(0.0, 0.01, 0.0))
    }

    // Glute bridge
    val bridgeStart = supine()
    val bridgeTop = bridgeStart.bridged(-126.0)
    list += motion("glute-bridge", side, listOf(mat(-0.25)), "start" to bridgeStart, "top" to bridgeTop)

    // Bridge march: hold the bridge, lift one knee then the other.
    run {
        val hip = bridgeTop.at("thigh_l")
        val lifted = LegPose(ik = true, ankle = hip + Vec3(0.02, 0.36, 0.40), kneeDir = Vec3(0.0, 1.0, 0.6), footWorld = true, footPitch = 10.0)
        list += motion("glute-bridge-march", side, listOf(mat(-0.25)), "start" to bridgeTop, "knee" to bridgeTop.copy(legL = lifted))
    }

    // Single-leg glute bridge (left foot planted, right leg reaching long).
    run {
        val planted = flat(0.08, 0.38, 0.0, Vec3(0.0, 1.0, 0.25))
        fun freeLeg(p: Pose): LegPose {
            val hip = p.at("thigh_r")
            val knee = p.at("calf_l")
            val dir = (knee - p.at("thigh_l")).normalized()
            return LegPose(ik = true, ankle = hip + dir * (legLength - 0.02), kneeDir = Vec3(0.0, 1.0, 0.0), footWorld = true, footPitch = -25.0)
        }
        val s0 = supine().copy(legL = planted)
        val t0 = s0.bridged(-128.0)
        list += motion("single-leg-glute-bridge", side, listOf(mat(-0.25)), "start" to s0.copy(legR = freeLeg(s0)), "top" to t0.copy(legR = freeLeg(t0)))
    }

    // Hip thrust: shoulder blades on the edge of a couch, drive the hips up until the thighs are flat.
    fun thrust(id: String, singleLeg: Boolean) {
        val couch = box(0.0, -0.47, 0.95, 0.5, 0.42)
        val edge = Vec3(0.0, 0.50, -0.27)
        val feet = flat(0.15, 0.48, 6.0, Vec3(0.1, 1.0, 0.4))
        // Arms resting along the couch top, palms down.
        val arms = ArmPose(ik = true, wrist = Vec3(0.50, 0.445, -0.40), elbowDir = Vec3(0.3, -0.3, -1.0), handWorld = true,
            fingerDir = Vec3(0.3, 0.0, -1.0), palmDir = -Vec3.Y, curl = 0.25, thumb = 0.1)
        var top = Pose(pelvis = Vec3.ZERO, pitch = -90.0, neckFlex = 28.0).symLegs(feet).symArms(arms).anchor("spine_03", edge)
        var low = Pose(pelvis = Vec3.ZERO, pitch = -38.0, neckFlex = 26.0, spineFlex = 6.0).symLegs(feet).symArms(arms).anchor("spine_03", edge + Vec3(0.0, 0.0, 0.02))
        if (singleLeg) {
            fun free(p: Pose): LegPose {
                val hip = p.at("thigh_r")
                return LegPose(ik = true, ankle = hip + Vec3(0.0, 0.42, 0.36), kneeDir = Vec3(0.0, 1.0, 0.5), footWorld = true, footPitch = 15.0)
            }
            top = top.copy(legL = flat(0.09, 0.48, 4.0, Vec3(0.05, 1.0, 0.4))); low = low.copy(legL = top.legL)
            top = top.copy(legR = free(top)); low = low.copy(legR = free(low))
        }
        list += motion(id, CameraSpec(62.0, 12.0), listOf(couch), "start" to low, "top" to top)
    }
    thrust("hip-thrust", false)
    thrust("single-leg-hip-thrust", true)

    // Frog pump: soles together, knees out, quick squeeze up.
    run {
        val leg = LegPose(ik = true, ankle = Vec3(0.055, 0.075, 0.30), kneeDir = Vec3(1.0, 0.7, 0.2), footWorld = true, footRoll = 68.0, footYaw = 8.0)
        val start = supine().symLegs(leg)
        val top = start.bridged(-112.0)
        list += motion("frog-pump", CameraSpec(40.0, 20.0), listOf(mat(-0.25)), "start" to start, "top" to top)
    }

    // Towel hamstring curl: hips up, heels slide out on a towel and back in.
    run {
        val heelsIn = LegPose(ik = true, ankle = Vec3(0.12, 0.08, 0.42), kneeDir = Vec3(0.0, 1.0, 0.2), footWorld = true, footPitch = -38.0)
        val start = supine().symLegs(heelsIn).bridged(-122.0)
        val out = LegPose(ik = true, ankle = Vec3(0.12, 0.08, 0.80), kneeDir = Vec3(0.0, 1.0, 0.1), footWorld = true, footPitch = -50.0)
        val bottom = start.symLegs(out).bridged(-104.0, from = start)
        list += motion("towel-hamstring-curl", side, listOf(mat(-0.25), Prop(PropKind.TOWEL, Vec3(0.0, 0.006, 0.62), Vec3(0.5, 0.008, 0.55))),
            "start" to start, "bottom" to bottom)
    }

    // Dead bug: arms up, knees over hips; lower the right arm and left leg, then switch.
    run {
        val up = ArmPose(flex = 88.0, abd = 4.0, elbow = 6.0, curl = 0.3)
        val table = LegPose(hipFlex = 90.0, knee = 90.0, footPitch = 10.0)
        val start = Pose(pelvis = Vec3(0.0, 0.112, 0.0), pitch = -94.0, spineFlex = 3.0, neckFlex = 10.0).symLegs(table).symArms(up)
        val reach = start.copy(armR = ArmPose(flex = 168.0, abd = 6.0, elbow = 8.0, curl = 0.3, clavElev = 10.0), legL = LegPose(hipFlex = 22.0, knee = 6.0, footPitch = 20.0))
        list += motion("dead-bug", side, listOf(mat(-0.2)), "start" to start, "reach" to reach)
    }

    // Tuck hollow hold: lower back down, shoulders and tucked knees lifted, arms long.
    run {
        val p = Pose(pelvis = Vec3(0.0, 0.11, 0.0), pitch = -96.0, spineFlex = 24.0, neckFlex = 14.0)
            .symLegs(LegPose(hipFlex = 102.0, knee = 105.0, footPitch = 25.0))
            .symArms(ArmPose(flex = 28.0, abd = 6.0, elbow = 4.0, curl = 0.2))
        val low = p.at("spine_01")
        list += motion("hollow-hold", side, listOf(mat(-0.2)), "start" to p.anchor("spine_01", Vec3(low.x, 0.122, low.z)))
    }

    // Reverse crunch: curl the hips off the floor towards the chest, lower slowly.
    run {
        val start = Pose(pelvis = Vec3(0.0, 0.112, 0.0), pitch = -90.0, neckFlex = 8.0)
            .symLegs(LegPose(hipFlex = 88.0, knee = 92.0, footPitch = 15.0)).symArms(floorArm)
        val chest = start.at("spine_03")
        val top = start.copy(pitch = -126.0, spineFlex = 36.0, legL = LegPose(hipFlex = 104.0, knee = 98.0, footPitch = 15.0), legR = LegPose(hipFlex = 104.0, knee = 98.0, footPitch = 15.0))
            .anchor("spine_03", chest)
        list += motion("reverse-crunch", side, listOf(mat(-0.2)), "start" to start, "top" to top)
    }

    // Figure-4 stretch (left ankle over the right knee), hands pulling the right thigh in.
    run {
        val base = Pose(pelvis = Vec3(0.0, 0.112, 0.0), pitch = -90.0, neckFlex = 10.0)
            .copy(legR = LegPose(hipFlex = 92.0, hipAbd = 4.0, knee = 92.0, footPitch = 10.0))
        val knee = base.at("calf_r")
        val crossed = LegPose(ik = true, ankle = knee + Vec3(0.02, 0.06, -0.02), kneeDir = Vec3(1.0, 0.5, 0.3), footWorld = true, footYaw = -80.0, footPitch = -10.0, footRoll = 70.0)
        val thigh = base.at("thigh_r").lerp(knee, 0.7)
        val hold = { x: Double -> ArmPose(ik = true, wrist = thigh + Vec3(x, -0.04, -0.06), elbowDir = Vec3(if (x > 0) 1.0 else -1.0, 0.2, -0.5), curl = 0.65, thumb = 0.5) }
        list += motion("figure-four-stretch", CameraSpec(35.0, 34.0), listOf(mat(-0.2)), "start" to base.copy(legL = crossed, armL = hold(0.06), armR = hold(-0.07)))
    }

    // Hamstring stretch (left leg up, soft knee), hands behind the calf.
    run {
        // Shoulders lift a little so the hands reach behind the knee.
        val base = Pose(pelvis = Vec3(0.0, 0.112, 0.0), pitch = -92.0, spineFlex = 16.0, neckFlex = 14.0)
            .copy(legL = LegPose(hipFlex = 76.0, knee = 12.0, footPitch = -5.0), legR = LegPose(hipFlex = 2.0, knee = 4.0, footPitch = 25.0))
        val calf = base.at("thigh_l").lerp(base.at("calf_l"), 0.66)
        val hold = { x: Double -> ArmPose(ik = true, wrist = calf + Vec3(x, -0.02, -0.07), elbowDir = Vec3(if (x > 0) 1.0 else -1.0, 0.0, -0.6), curl = 0.65, thumb = 0.5) }
        list += motion("hamstring-stretch", CameraSpec(70.0, 18.0), listOf(mat(-0.2)), "start" to base.copy(armL = hold(0.05), armR = hold(-0.05)))
    }

    return list
}
