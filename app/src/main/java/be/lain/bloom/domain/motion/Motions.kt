package be.lain.bloom.domain.motion

/**
 * Hand-authored keyframes for every exercise, built on the figure's rig. Feet and hands that should
 * stay planted use inverse kinematics with the same target in every keyframe, so they never slide.
 *
 * Conventions (see [Pose]): the figure faces +Z, floor y = 0. Lying on the back = pitch −90 with the
 * head towards −Z; face down = pitch +90 with the head towards +Z.
 */
class MotionLibrary(val solver: Solver) {
    val rig = solver.rig
    val ankleH = solver.ankleHeight
    private val ballH = rig.rest("ball_l").y
    val restPelvis = solver.restPelvis
    val legLength = solver.thighLength + solver.shinLength
    /** Wrist joint height when the palm is flat on the floor. */
    val wristH = 0.024

    // ---------------------------------------------------------------- building blocks

    /** Foot flat on the floor; [yaw] turns the toes outwards for the left foot. */
    fun flat(x: Double, z: Double, yaw: Double = 0.0, knee: Vec3 = Vec3.Z) =
        LegPose(ik = true, ankle = Vec3(x, ankleH, z), kneeDir = knee.normalized(), footWorld = true, footYaw = yaw)

    /** On the ball of the foot (toes flat on the floor), heel raised by [heel] degrees. */
    fun onBall(x: Double, z: Double, heel: Double, yaw: Double = 0.0, knee: Vec3 = Vec3.Z): LegPose {
        val rot = Quat.ry(yaw) * Quat.rx(heel)
        val ankle = Vec3(x, ballH, z) - rot.rotate(solver.ballOffset)
        return LegPose(ik = true, ankle = ankle, kneeDir = knee.normalized(), footWorld = true, footPitch = heel, footYaw = yaw, toes = heel)
    }

    /** Palm flat on the floor with the wrist joint at (x, z); fingers point along [fingers]. */
    fun palm(x: Double, z: Double, elbow: Vec3, fingers: Vec3 = Vec3.Z, curl: Double = 0.08) =
        ArmPose(ik = true, wrist = Vec3(x, wristH, z), elbowDir = elbow.normalized(), handWorld = true,
            fingerDir = fingers.normalized(), palmDir = -Vec3.Y, curl = curl, thumb = -0.4)

    val relaxedArm = ArmPose(flex = 4.0, abd = 7.0, elbow = 14.0, curl = 0.38, thumb = 0.3)

    fun stand(width: Double = 0.11, toeOut: Double = 6.0, drop: Double = 0.008): Pose {
        val leg = flat(width, -0.07, toeOut, Vec3(kotlin.math.sin(Math.toRadians(toeOut)), 0.0, 1.0))
        return Pose(pelvis = restPelvis - Vec3(0.0, drop, 0.0), legL = leg, legR = leg.mirrored(), armL = relaxedArm, armR = relaxedArm)
    }

    /** Same leg on both sides (right = mirror of [left]). */
    fun Pose.symLegs(left: LegPose) = copy(legL = left, legR = left.mirrored())
    fun Pose.symArms(left: ArmPose) = copy(armL = left, armR = left.mirrored())

    /** Moves the whole pose so that [joint] lands on [target]. */
    fun Pose.anchor(joint: String, target: Vec3): Pose {
        val cur = solver.solve(this).world[rig[joint]]
        return copy(pelvis = pelvis + (target - cur))
    }

    fun Pose.at(joint: String): Vec3 = solver.solve(this).world[rig[joint]]

    fun mat(zCenter: Double = 0.0, xCenter: Double = 0.0, yaw: Double = 0.0) = Prop(PropKind.MAT, Vec3(xCenter, 0.003, zCenter), Vec3(0.62, 0.006, 1.83), yaw)

    /** A box standing on the floor: centre x/z, footprint and height. */
    fun box(x: Double, z: Double, width: Double, depth: Double, height: Double, bottom: Double = 0.0) =
        Prop(PropKind.BOX, Vec3(x, bottom + height / 2, z), Vec3(width, height, depth))

    fun motion(id: String, camera: CameraSpec, props: List<Prop> = emptyList(), vararg poses: Pair<String, Pose>) =
        Motion(id, Tempos[id], poses.toMap(), camera, props)

    val handsOnHips = ArmPose(flex = -14.0, abd = 44.0, rot = -88.0, elbow = 96.0, wristFlex = 10.0, curl = 0.2, thumb = 0.1)
    val handsBehindHead = ArmPose(flex = 30.0, abd = 95.0, rot = 75.0, elbow = 140.0, curl = 0.35, clavElev = 8.0)
    fun armsForward(flex: Double, elbow: Double = 8.0) = ArmPose(flex = flex, abd = 6.0, elbow = elbow, curl = 0.3, thumb = 0.3)

    // ---------------------------------------------------------------- exercises

    private fun bodyweightSquat(): Motion {
        val toe = 14.0
        val knee = Vec3(kotlin.math.sin(Math.toRadians(toe + 4)), 0.0, 1.0)
        val start = stand().symLegs(flat(0.17, -0.06, toe, knee))
        val bottom = start.copy(
            pelvis = Vec3(0.0, 0.50, -0.25), pitch = 40.0, spineFlex = -6.0, neckFlex = -18.0, headFlex = -10.0,
        ).symArms(ArmPose(flex = 118.0, abd = 6.0, elbow = 8.0, curl = 0.3, thumb = 0.3))
        return motion("bodyweight-squat", CameraSpec(38.0, 8.0), emptyList(), "start" to start, "bottom" to bottom)
    }

    private fun pushup(): Motion {
        fun body(beta: Double): Pose {
            val heel = 72.0 - beta * 0.6
            val legL = onBall(0.10, -0.62, heel)
            val p = Pose(pelvis = Vec3.ZERO, pitch = 90.0 - beta, neckFlex = -8.0).symLegs(legL)
            // Straight legs: hip sits one leg-length from the ankle along the body line.
            val dir = Vec3(0.0, kotlin.math.sin(Math.toRadians(beta)), kotlin.math.cos(Math.toRadians(beta)))
            val hip = legL.ankle + dir * (legLength - 0.004)
            return p.anchor("thigh_l", Vec3(rig.rest("thigh_l").x, hip.y, hip.z))
        }
        val top = body(14.5)
        val bottom = body(7.5).copy(neckFlex = -4.0)
        // Hands planted a little wider than the shoulders, level with the chest at the bottom.
        val sh = bottom.at("upperarm_l")
        val arm = palm(sh.x + 0.07, sh.z - 0.03, Vec3(0.55, 0.15, -0.8))
        val topP = top.symArms(arm)
        val bottomP = bottom.symArms(arm)
        return motion("pushup", CameraSpec(62.0, 12.0), listOf(mat(0.15)), "start" to topP, "bottom" to bottomP)
    }

    val all: Map<String, Motion> by lazy {
        (listOf(bodyweightSquat(), pushup()) + standingMotions() + supineMotions() + proneMotions() + sideMotions() + propMotions() + warmupMotions()).associateBy { it.id }
    }

    operator fun get(id: String): Motion? = all[id]
}
