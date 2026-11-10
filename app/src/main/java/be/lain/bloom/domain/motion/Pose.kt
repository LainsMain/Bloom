package be.lain.bloom.domain.motion

/**
 * One leg. Forward kinematics uses anatomical angles (degrees, relative to the pelvis, standing
 * neutral = 0): flex lifts the thigh forward, abd moves it out to the side, rot turns the knee out,
 * knee bends the shin back. Inverse kinematics plants the ankle joint at [ankle] (world) with the knee
 * pointing along [kneeDir].
 */
data class LegPose(
    val ik: Boolean = false,
    val hipFlex: Double = 0.0,
    val hipAbd: Double = 0.0,
    val hipRot: Double = 0.0,
    val knee: Double = 0.0,
    val ankle: Vec3 = Vec3.ZERO,
    val kneeDir: Vec3 = Vec3.Z,
    /** Foot orientation in world space (yaw/pitch/roll) instead of relative to the shin. */
    val footWorld: Boolean = false,
    /** World: heel raise (+) / toes up (−). Relative: plantarflexion (+, pointing the foot). */
    val footPitch: Double = 0.0,
    val footYaw: Double = 0.0,
    val footRoll: Double = 0.0,
    /** Toe bend upwards (as when standing on the balls of the feet). */
    val toes: Double = 0.0,
) {
    fun lerp(o: LegPose, t: Double): LegPose {
        require(ik == o.ik) { "Can't blend IK and FK legs" }
        return LegPose(
            ik, lerp(hipFlex, o.hipFlex, t), lerp(hipAbd, o.hipAbd, t), lerp(hipRot, o.hipRot, t), lerp(knee, o.knee, t),
            ankle.lerp(o.ankle, t), kneeDir.lerp(o.kneeDir, t).normalized(), if (t < 0.5) footWorld else o.footWorld,
            lerp(footPitch, o.footPitch, t), lerp(footYaw, o.footYaw, t), lerp(footRoll, o.footRoll, t), lerp(toes, o.toes, t),
        )
    }

    fun mirrored() = copy(ankle = ankle.mirrored(), kneeDir = kneeDir.mirrored(), footYaw = -footYaw, footRoll = -footRoll)
}

/**
 * One arm. Forward kinematics: angles (degrees) relative to the arm hanging by the side with the palm
 * facing the thigh — flex raises it forward, abd out to the side, rot turns it outward, elbow bends.
 * Inverse kinematics plants the wrist joint at [wrist] with the elbow pointing along [elbowDir].
 */
data class ArmPose(
    val ik: Boolean = false,
    val flex: Double = 0.0,
    val abd: Double = 0.0,
    val rot: Double = 0.0,
    val elbow: Double = 0.0,
    val wrist: Vec3 = Vec3.ZERO,
    val elbowDir: Vec3 = -Vec3.Z,
    /** Hand orientation from world directions instead of wrist angles. */
    val handWorld: Boolean = false,
    val fingerDir: Vec3 = Vec3.Z,
    val palmDir: Vec3 = -Vec3.Y,
    val wristFlex: Double = 0.0,
    val pronation: Double = 0.0,
    /** 0 = flat hand, ~0.35 relaxed, 1 = fist. */
    val curl: Double = 0.35,
    val thumb: Double = 0.3,
    val clavElev: Double = 0.0,
    val clavProt: Double = 0.0,
) {
    fun lerp(o: ArmPose, t: Double): ArmPose {
        require(ik == o.ik) { "Can't blend IK and FK arms" }
        return ArmPose(
            ik, lerp(flex, o.flex, t), lerp(abd, o.abd, t), lerp(rot, o.rot, t), lerp(elbow, o.elbow, t),
            wrist.lerp(o.wrist, t), elbowDir.lerp(o.elbowDir, t).normalized(), if (t < 0.5) handWorld else o.handWorld,
            fingerDir.lerp(o.fingerDir, t).normalized(), palmDir.lerp(o.palmDir, t).normalized(),
            lerp(wristFlex, o.wristFlex, t), lerp(pronation, o.pronation, t), lerp(curl, o.curl, t), lerp(thumb, o.thumb, t),
            lerp(clavElev, o.clavElev, t), lerp(clavProt, o.clavProt, t),
        )
    }

    fun mirrored() = copy(wrist = wrist.mirrored(), elbowDir = elbowDir.mirrored(), fingerDir = fingerDir.mirrored(), palmDir = palmDir.mirrored())
}

/**
 * A whole-body keyframe. The pelvis is placed in world space and oriented by yaw (turn left +),
 * pitch (tip the trunk forward +; −90 lies on the back, +90 face down) and roll (lean to the right +).
 * Spine/neck/head angles are degrees: flex forward +, side bend to the left +, twist to the left +.
 */
data class Pose(
    val pelvis: Vec3,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val spineFlex: Double = 0.0,
    val spineSide: Double = 0.0,
    val spineTwist: Double = 0.0,
    val neckFlex: Double = 0.0,
    val neckSide: Double = 0.0,
    val neckTwist: Double = 0.0,
    val headFlex: Double = 0.0,
    val headSide: Double = 0.0,
    val headTwist: Double = 0.0,
    val legL: LegPose = LegPose(),
    val legR: LegPose = LegPose(),
    val armL: ArmPose = ArmPose(),
    val armR: ArmPose = ArmPose(),
) {
    fun lerp(o: Pose, t: Double) = Pose(
        pelvis.lerp(o.pelvis, t), lerp(yaw, o.yaw, t), lerp(pitch, o.pitch, t), lerp(roll, o.roll, t),
        lerp(spineFlex, o.spineFlex, t), lerp(spineSide, o.spineSide, t), lerp(spineTwist, o.spineTwist, t),
        lerp(neckFlex, o.neckFlex, t), lerp(neckSide, o.neckSide, t), lerp(neckTwist, o.neckTwist, t),
        lerp(headFlex, o.headFlex, t), lerp(headSide, o.headSide, t), lerp(headTwist, o.headTwist, t),
        legL.lerp(o.legL, t), legR.lerp(o.legR, t), armL.lerp(o.armL, t), armR.lerp(o.armR, t),
    )

    /** The same pose done on the other side. */
    fun mirrored() = copy(
        pelvis = pelvis.mirrored(), yaw = -yaw, roll = -roll, spineSide = -spineSide, spineTwist = -spineTwist,
        neckSide = -neckSide, neckTwist = -neckTwist, headSide = -headSide, headTwist = -headTwist,
        legL = legR.mirrored(), legR = legL.mirrored(), armL = armR.mirrored(), armR = armL.mirrored(),
    )

    fun legs(f: (LegPose) -> LegPose) = copy(legL = f(legL), legR = f(legR))
    fun arms(f: (ArmPose) -> ArmPose) = copy(armL = f(armL), armR = f(armR))
}
