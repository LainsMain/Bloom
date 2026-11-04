package be.lain.bloom.domain.training

enum class ExerciseKind { WARMUP, STRENGTH, CORE, STRETCH }
enum class Mode { REPS, TIME }

data class Exercise(
    val id: String,
    val name: String,
    val kind: ExerciseKind,
    val mode: Mode,
    /** Done on each side (left then right). */
    val perSide: Boolean = false,
    val muscles: String,
    val cues: List<String>,
    val mistakes: List<String> = emptyList(),
    /** Same movement pattern, ordered easiest → hardest. */
    val ladder: String? = null,
    val equipment: String? = null,
)

object Exercises {
    /** Easier → harder variations per movement pattern. */
    val ladders: Map<String, List<String>> = mapOf(
        "squat" to listOf("box-squat", "bodyweight-squat", "split-squat", "bulgarian-split-squat"),
        "bridge" to listOf("glute-bridge", "glute-bridge-march", "single-leg-glute-bridge", "hip-thrust", "single-leg-hip-thrust"),
        "abduction" to listOf("side-lying-leg-raise", "clamshell", "fire-hydrant"),
        "lunge" to listOf("step-up", "reverse-lunge", "curtsy-lunge"),
        "hinge" to listOf("good-morning", "single-leg-rdl"),
        "push" to listOf("wall-pushup", "incline-pushup", "knee-pushup", "pushup"),
        "pull" to listOf("prone-ytw", "reverse-snow-angel", "doorway-row", "table-row"),
        "core" to listOf("dead-bug", "knee-plank", "plank", "plank-shoulder-tap"),
        "side" to listOf("side-plank-knees", "side-plank"),
    )

    val all: List<Exercise> = listOf(
        // ---- Warm-up ----
        Exercise("march-in-place", "March in place", ExerciseKind.WARMUP, Mode.TIME, muscles = "Whole body",
            cues = listOf("Lift knees to hip height if you can", "Swing opposite arm to knee", "Breathe easy — this is just warming up")),
        Exercise("step-jacks", "Step jacks", ExerciseKind.WARMUP, Mode.TIME, muscles = "Whole body, heart rate",
            cues = listOf("Step one foot out while arms go up", "Step back in, switch sides", "No jumping needed — keep it light")),
        Exercise("hip-circles", "Hip circles", ExerciseKind.WARMUP, Mode.TIME, muscles = "Hips",
            cues = listOf("Hands on hips, feet shoulder-width", "Draw big slow circles with your pelvis", "Switch direction halfway")),
        Exercise("cat-cow", "Cat-cow", ExerciseKind.WARMUP, Mode.TIME, muscles = "Spine, core",
            cues = listOf("On hands and knees, wrists under shoulders", "Round your back up, chin to chest (cat)", "Then let the belly drop and look up (cow)")),
        Exercise("arm-circles", "Arm circles", ExerciseKind.WARMUP, Mode.TIME, muscles = "Shoulders",
            cues = listOf("Arms out to the sides", "Start small, make the circles bigger", "Switch direction halfway")),
        Exercise("leg-swings", "Leg swings", ExerciseKind.WARMUP, Mode.TIME, perSide = true, muscles = "Hips, hamstrings",
            cues = listOf("Hold a wall or chair for balance", "Swing one leg forward and back, relaxed", "Let the swing get gradually bigger")),

        // ---- Lower body ----
        Exercise("box-squat", "Chair squat", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Glutes, thighs", ladder = "squat", equipment = "Chair",
            cues = listOf("Stand just in front of a chair, feet shoulder-width", "Push hips back and lightly tap the seat", "Drive through your heels to stand tall"),
            mistakes = listOf("Plopping down onto the chair", "Knees caving inward")),
        Exercise("bodyweight-squat", "Bodyweight squat", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Glutes, thighs", ladder = "squat",
            cues = listOf("Feet shoulder-width, toes slightly out", "Sit back and down, chest proud", "Knees follow your toes; stand by squeezing your glutes"),
            mistakes = listOf("Heels lifting", "Rounding the lower back at the bottom")),
        Exercise("sumo-squat", "Sumo squat", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Glutes, inner thighs",
            cues = listOf("Wide stance, toes turned out 30–45°", "Lower straight down, knees out over toes", "Pause 1 second at the bottom, squeeze up")),
        Exercise("split-squat", "Split squat", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes, thighs", ladder = "squat",
            cues = listOf("Long staggered stance, hold a wall if needed", "Drop the back knee straight down", "Push through the front heel"),
            mistakes = listOf("Front heel lifting", "Stance too short")),
        Exercise("bulgarian-split-squat", "Bulgarian split squat", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes, thighs",
            ladder = "squat", equipment = "Couch or chair",
            cues = listOf("Back foot on a couch, front foot two steps ahead", "Lean slightly forward for more glute", "Lower slowly (3 s), drive up through the front heel"),
            mistakes = listOf("Pushing off the back foot", "Front knee caving in")),
        Exercise("step-up", "Step-up", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes, thighs", ladder = "lunge",
            equipment = "Stair or sturdy step",
            cues = listOf("Whole foot on the step", "Lean slightly forward and push through that heel", "Step down slowly — don't push off the floor leg")),
        Exercise("reverse-lunge", "Reverse lunge", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes, thighs", ladder = "lunge",
            cues = listOf("Step one foot back and lower the back knee", "Keep most weight on the front heel", "Push back up to standing"),
            mistakes = listOf("Front knee drifting far past the toes", "Torso collapsing forward")),
        Exercise("curtsy-lunge", "Curtsy lunge", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes (side), thighs", ladder = "lunge",
            cues = listOf("Step one foot diagonally behind the other", "Lower with hips square to the front", "Push through the front heel to return")),
        Exercise("lateral-lunge", "Side lunge", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes, inner thighs",
            cues = listOf("Big step to the side", "Sit back into that hip, other leg straight", "Push back to the middle")),

        // ---- Glutes ----
        Exercise("glute-bridge", "Glute bridge", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Glutes, hamstrings", ladder = "bridge",
            cues = listOf("Feet flat, hip-width, close to your bum", "Tuck pelvis slightly, then press through heels", "Squeeze glutes hard at the top for 1–2 s"),
            mistakes = listOf("Arching the lower back instead of using glutes", "Ribs flaring up")),
        Exercise("glute-bridge-march", "Glute bridge march", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Glutes, core", ladder = "bridge",
            cues = listOf("Hold a bridge at the top", "Lift one knee, then the other", "Keep hips level — don't let them drop")),
        Exercise("single-leg-glute-bridge", "Single-leg glute bridge", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes, hamstrings",
            ladder = "bridge",
            cues = listOf("One foot down, other leg up", "Drive through the planted heel", "Keep hips level at the top")),
        Exercise("hip-thrust", "Hip thrust", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Glutes", ladder = "bridge", equipment = "Couch",
            cues = listOf("Upper back on the edge of the couch", "Chin tucked, ribs down", "Push hips up until thighs are flat; squeeze 2 s"),
            mistakes = listOf("Overarching the back at the top", "Feet too far away (hamstrings take over)")),
        Exercise("single-leg-hip-thrust", "Single-leg hip thrust", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes",
            ladder = "bridge", equipment = "Couch",
            cues = listOf("Upper back on the couch, one foot planted", "Drive up through that heel", "Pause at the top, lower slowly")),
        Exercise("frog-pump", "Frog pump", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Glutes",
            cues = listOf("Soles of feet together, knees out", "Heels close to your bum", "Squeeze up quickly, lower with control")),
        Exercise("donkey-kick", "Donkey kick", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Glutes",
            cues = listOf("On all fours, back flat", "Keep the knee bent 90° and press the sole to the ceiling", "Don't arch your back — stop when the hip is straight")),
        Exercise("fire-hydrant", "Fire hydrant", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Side glutes", ladder = "abduction",
            cues = listOf("On all fours, core tight", "Lift the bent knee out to the side", "Keep hips square — no leaning")),
        Exercise("clamshell", "Clamshell", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Side glutes", ladder = "abduction",
            cues = listOf("Lie on your side, knees bent, feet together", "Open the top knee like a clam", "Keep hips stacked — don't roll back")),
        Exercise("side-lying-leg-raise", "Side-lying leg raise", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Side glutes",
            ladder = "abduction",
            cues = listOf("Lie on your side, body in a line", "Lift the top leg, toes pointing forward", "Slightly behind your body, lower slowly")),

        // ---- Hinge ----
        Exercise("good-morning", "Good morning", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Hamstrings, glutes, back", ladder = "hinge",
            cues = listOf("Hands behind head, soft knees", "Push hips back, back flat, until you feel hamstrings", "Squeeze glutes to stand")),
        Exercise("single-leg-rdl", "Single-leg Romanian deadlift", ExerciseKind.STRENGTH, Mode.REPS, perSide = true, muscles = "Hamstrings, glutes, balance",
            ladder = "hinge",
            cues = listOf("Stand on one leg, slight knee bend", "Hinge forward as the free leg reaches back", "Hips stay square; touch a wall if needed")),
        Exercise("towel-hamstring-curl", "Towel hamstring curl", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Hamstrings, glutes",
            equipment = "Towel on a smooth floor",
            cues = listOf("Heels on a towel, hips lifted in a bridge", "Slide heels out slowly, then pull them back in", "Keep hips up the whole time")),

        // ---- Upper body ----
        Exercise("wall-pushup", "Wall push-up", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Chest, arms, shoulders", ladder = "push",
            cues = listOf("Hands on wall at shoulder height", "Body in one straight line", "Lower chest to the wall, elbows angled back")),
        Exercise("incline-pushup", "Incline push-up", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Chest, arms, shoulders", ladder = "push",
            equipment = "Kitchen counter or sturdy table",
            cues = listOf("Hands on the counter edge, shoulder-width", "Squeeze glutes and abs — moving plank", "Lower your chest to the edge, push away")),
        Exercise("knee-pushup", "Knee push-up", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Chest, arms, shoulders", ladder = "push",
            cues = listOf("Knees down, straight line from head to knees", "Hands a bit wider than shoulders", "Lower chest to the floor with control")),
        Exercise("pushup", "Push-up", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Chest, arms, shoulders, core", ladder = "push",
            cues = listOf("Straight line from head to heels", "Elbows about 45° from your body", "Chest to the floor, push it away")),
        Exercise("bench-dip", "Chair dip", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Back of the arms", equipment = "Sturdy chair",
            cues = listOf("Hands on the chair edge, fingers forward", "Keep your back close to the chair", "Bend elbows to ~90°, press back up"),
            mistakes = listOf("Elbows flaring out", "Shoulders shrugging up")),
        Exercise("prone-ytw", "Y-T-W raises", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Upper back, posture", ladder = "pull",
            cues = listOf("Lie face down, forehead on a towel", "Lift arms in a Y, then T, then W — that's 1 rep", "Squeeze shoulder blades together, thumbs up")),
        Exercise("reverse-snow-angel", "Reverse snow angel", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Upper back, posture", ladder = "pull",
            cues = listOf("Lie face down, arms by your sides, hovering", "Sweep arms overhead in a big arc", "Keep them off the floor the whole way")),
        Exercise("doorway-row", "Doorframe row", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Back, biceps", ladder = "pull", equipment = "Doorframe",
            cues = listOf("Hold both sides of a doorframe, lean back", "Pull your chest towards the frame", "Squeeze shoulder blades together")),
        Exercise("table-row", "Table row", ExerciseKind.STRENGTH, Mode.REPS, muscles = "Back, biceps", ladder = "pull", equipment = "Very sturdy table",
            cues = listOf("Lie under a heavy table, grip the edge", "Body straight (or knees bent to make it easier)", "Pull your chest to the table, lower slowly"),
            mistakes = listOf("Using a table that can tip — test it first!")),

        // ---- Core ----
        Exercise("dead-bug", "Dead bug", ExerciseKind.CORE, Mode.REPS, perSide = true, muscles = "Deep core", ladder = "core",
            cues = listOf("On your back, arms up, knees over hips", "Press lower back into the floor", "Lower opposite arm and leg slowly, breathe out")),
        Exercise("bird-dog", "Bird dog", ExerciseKind.CORE, Mode.REPS, perSide = true, muscles = "Core, glutes, back",
            cues = listOf("On all fours, back flat", "Reach opposite arm and leg long", "Hold 2 s without wobbling, switch")),
        Exercise("knee-plank", "Knee plank", ExerciseKind.CORE, Mode.TIME, muscles = "Core", ladder = "core",
            cues = listOf("Forearms down, knees down", "Straight line from head to knees", "Squeeze glutes, belly button in")),
        Exercise("plank", "Forearm plank", ExerciseKind.CORE, Mode.TIME, muscles = "Core", ladder = "core",
            cues = listOf("Elbows under shoulders", "Straight line from head to heels", "Push the floor away, squeeze glutes"),
            mistakes = listOf("Hips sagging", "Holding your breath")),
        Exercise("plank-shoulder-tap", "Plank shoulder taps", ExerciseKind.CORE, Mode.REPS, perSide = true, muscles = "Core, shoulders", ladder = "core",
            cues = listOf("High plank, feet a bit wider", "Tap the opposite shoulder slowly", "Hips stay still — no rocking")),
        Exercise("side-plank-knees", "Side plank on knees", ExerciseKind.CORE, Mode.TIME, perSide = true, muscles = "Side core, waist", ladder = "side",
            cues = listOf("Elbow under shoulder, knees bent", "Lift hips into a straight line", "Don't let the hips sag")),
        Exercise("side-plank", "Side plank", ExerciseKind.CORE, Mode.TIME, perSide = true, muscles = "Side core, waist", ladder = "side",
            cues = listOf("Elbow under shoulder, feet stacked", "Lift hips high in one line", "Breathe steadily")),
        Exercise("hollow-hold", "Tuck hollow hold", ExerciseKind.CORE, Mode.TIME, muscles = "Deep core",
            cues = listOf("Lower back glued to the floor", "Lift shoulders and tucked knees", "Arms long by your sides")),
        Exercise("reverse-crunch", "Reverse crunch", ExerciseKind.CORE, Mode.REPS, muscles = "Lower abs",
            cues = listOf("On your back, knees bent at 90°", "Curl hips off the floor towards your chest", "Lower slowly — no swinging")),
        Exercise("stomach-vacuum", "Stomach vacuum", ExerciseKind.CORE, Mode.TIME, muscles = "Deep core (transverse abdominis)",
            cues = listOf("Breathe all the air out", "Pull your belly button in towards your spine", "Hold, breathing shallowly — this flattens the waistline from inside")),

        // ---- Cool-down ----
        Exercise("hip-flexor-stretch", "Hip flexor stretch", ExerciseKind.STRETCH, Mode.TIME, perSide = true, muscles = "Front of hips",
            cues = listOf("Kneel on one knee (cushion under it)", "Tuck your pelvis and squeeze that glute", "Shift gently forward until you feel the front of the hip")),
        Exercise("figure-four-stretch", "Figure-4 stretch", ExerciseKind.STRETCH, Mode.TIME, perSide = true, muscles = "Glutes",
            cues = listOf("On your back, ankle over the opposite knee", "Pull the bottom thigh towards you", "Relax and breathe")),
        Exercise("hamstring-stretch", "Hamstring stretch", ExerciseKind.STRETCH, Mode.TIME, perSide = true, muscles = "Hamstrings",
            cues = listOf("On your back, one leg up (towel around foot helps)", "Keep a soft knee", "Gentle pull — never painful")),
        Exercise("childs-pose", "Child's pose", ExerciseKind.STRETCH, Mode.TIME, muscles = "Back, hips",
            cues = listOf("Knees wide, big toes together", "Sit back to your heels, arms long", "Slow, deep breaths")),
    )

    private val byId = all.associateBy { it.id }
    operator fun get(id: String): Exercise = byId[id] ?: error("Unknown exercise $id")
    fun find(id: String): Exercise? = byId[id]

    fun easier(id: String): String? = byId[id]?.ladder?.let { l -> ladders[l]?.let { it.getOrNull(it.indexOf(id) - 1) } }
    fun harder(id: String): String? = byId[id]?.ladder?.let { l -> ladders[l]?.let { val i = it.indexOf(id); if (i >= 0) it.getOrNull(i + 1) else null } }
}
