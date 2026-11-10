package be.lain.bloom.domain.training

import be.lain.bloom.domain.training.Program.cooldown
import be.lain.bloom.domain.training.Program.p
import be.lain.bloom.domain.training.Program.t
import be.lain.bloom.domain.training.Program.warmup

/** All strength programs. Every exercise comes from the library, so each has cues and a 3-D demo. */
object Programs {
    private fun main(vararg items: Prescription) = Block(BlockKind.MAIN, items.toList())
    private fun core(vararg items: Prescription) = Block(BlockKind.CORE, items.toList())

    private val quickWarmup = Block(BlockKind.WARMUP, listOf(
        t("march-in-place", 1, 45, rest = 0), t("hip-circles", 1, 30, rest = 0), t("arm-circles", 1, 30, rest = 0),
    ))
    private val quickCooldown = Block(BlockKind.COOLDOWN, listOf(t("hip-flexor-stretch", 1, 25, rest = 0), t("childs-pose", 1, 30, rest = 0)))
    private val gentleWarmup = Block(BlockKind.WARMUP, listOf(
        t("march-in-place", 1, 60, rest = 0), t("hip-circles", 1, 30, rest = 0), t("cat-cow", 1, 30, rest = 0), t("arm-circles", 1, 30, rest = 0),
    ))
    private val coreWarmup = Block(BlockKind.WARMUP, listOf(
        t("cat-cow", 1, 40, rest = 0), t("hip-circles", 1, 30, rest = 0), t("arm-circles", 1, 30, rest = 0), t("march-in-place", 1, 45, rest = 0),
    ))
    private val stretchCooldown = Block(BlockKind.COOLDOWN, listOf(
        t("hamstring-stretch", 1, 25, rest = 0), t("hip-flexor-stretch", 1, 25, rest = 0), t("childs-pose", 1, 40, rest = 0),
    ))

    /** The gentlest way in: three short full-body sessions a week before moving to a longer program. */
    val starter15 = TrainingProgram(
        id = "starter-15",
        name = "Starter 15",
        tagline = "Start here: 15 minutes, three times a week",
        description = "Your first six weeks: three full-body sessions a week of about 15 minutes. Two easy sets of each move with short rests, so the habit sticks before the workouts get longer. Then move on to Feminine Shape or Glute Builder.",
        level = "Complete beginner",
        sessionsPerWeek = 3,
        focus = listOf("Full body", "Short", "Habit"),
        equipment = "A chair, a wall, a doorframe and a kitchen counter",
        phases = listOf(
            Phase(0, "First steps", 3, "Two easy sets of four moves. Finish feeling like you could do more — that's the point.", listOf(
                Session("A", "Starter A", "Chair squat · wall push-up · bridge · core", listOf(
                    quickWarmup,
                    main(p("box-squat", 2, 8, 10, rest = 30), p("wall-pushup", 2, 8, 10, rest = 30), p("glute-bridge", 2, 10, 12, rest = 30)),
                    core(p("dead-bug", 2, 5, 6, rest = 30)), quickCooldown,
                )),
                Session("B", "Starter B", "Sumo squat · row · side glutes · core", listOf(
                    quickWarmup,
                    main(p("sumo-squat", 2, 8, 10, rest = 30), p("doorway-row", 2, 8, 10, rest = 30), p("side-lying-leg-raise", 2, 10, 12, rest = 20)),
                    core(t("knee-plank", 2, 15, rest = 30)), quickCooldown,
                )),
            )),
            Phase(1, "Building", 3, "Slightly harder versions, same short sessions. When these feel easy, switch to Feminine Shape or Glute Builder.", listOf(
                Session("A", "Starter A", "Squat · incline push-up · bridge march · core", listOf(
                    quickWarmup,
                    main(p("bodyweight-squat", 2, 10, 12, rest = 30), p("incline-pushup", 2, 8, 10, rest = 30), p("glute-bridge-march", 2, 6, 8, rest = 30)),
                    core(p("bird-dog", 2, 5, 6, rest = 30)), quickCooldown,
                )),
                Session("B", "Starter B", "Lunge · row · clamshell · core", listOf(
                    quickWarmup,
                    main(p("reverse-lunge", 2, 6, 8, rest = 30), p("doorway-row", 2, 10, 12, rest = 30), p("clamshell", 2, 10, 12, rest = 20)),
                    core(t("plank", 2, 20, rest = 30)), quickCooldown,
                )),
            )),
        ),
    )

    val gluteBuilder = TrainingProgram(
        id = "glute-builder",
        name = "Glute Builder",
        tagline = "Rounder, stronger glutes",
        description = "Three glute-focused sessions a week: thrusts and squats, lunges and hinges, then a pump and side-glute day. Light upper body, a little core.",
        level = "Beginner → intermediate",
        sessionsPerWeek = 3,
        focus = listOf("Glutes", "Hips", "Legs"),
        equipment = "A chair or sofa and a low step",
        phases = listOf(
            Phase(0, "Activate", 4, "Learn to feel your glutes working. Slow reps, squeeze at the top, stop with 2–3 reps left.", listOf(
                Session("A", "Activate A", "Bridge · squat · side glutes", listOf(
                    warmup("glute-bridge"),
                    main(p("glute-bridge", 3, 12, 15, note = "Squeeze 2 s at the top"), p("box-squat", 3, 10, 12),
                        p("clamshell", 2, 12, 15, rest = 30), p("side-lying-leg-raise", 2, 12, 15, rest = 30)),
                    core(p("dead-bug", 2, 6, 8, rest = 45)), cooldown,
                )),
                Session("B", "Activate B", "Step-up · hinge · kickback", listOf(
                    warmup("step-jacks"),
                    main(p("step-up", 3, 8, 10, note = "Push through the heel"), p("good-morning", 3, 10, 12, rest = 45),
                        p("donkey-kick", 2, 12, 15, rest = 30), p("frog-pump", 2, 15, 20, rest = 30)),
                    core(t("side-plank-knees", 2, 20, rest = 30)), cooldown,
                )),
                Session("C", "Activate C", "Bridge march · sumo · hydrant", listOf(
                    warmup("glute-bridge"),
                    main(p("glute-bridge-march", 3, 8, 10), p("sumo-squat", 3, 10, 12), p("fire-hydrant", 2, 10, 12, rest = 30),
                        p("reverse-lunge", 2, 8, 10, rest = 45)),
                    core(p("bird-dog", 2, 6, 8, rest = 30)), cooldown,
                )),
            )),
            Phase(1, "Grow", 6, "More volume and single-leg work. Add reps each week; level up when the top of the range feels easy.", listOf(
                Session("A", "Grow A", "Hip thrust · split squat · pump", listOf(
                    warmup("glute-bridge"),
                    main(p("hip-thrust", 3, 12, 15, note = "2 s squeeze at the top"), p("split-squat", 3, 8, 10),
                        p("frog-pump", 3, 15, 20, rest = 30), p("side-lying-leg-raise", 3, 15, 20, rest = 30)),
                    core(t("plank", 3, 25, rest = 30)), cooldown,
                )),
                Session("B", "Grow B", "Bulgarian · single-leg hinge · kickback", listOf(
                    warmup("step-jacks"),
                    main(p("bulgarian-split-squat", 3, 8, 10, note = "3 s down"), p("single-leg-rdl", 3, 8, 10, rest = 45),
                        p("donkey-kick", 3, 12, 15, rest = 30), p("curtsy-lunge", 2, 8, 10, rest = 45)),
                    core(t("side-plank", 2, 20, rest = 30)), cooldown,
                )),
                Session("C", "Grow C", "Single-leg bridge · sumo · hamstrings", listOf(
                    warmup("glute-bridge"),
                    main(p("single-leg-glute-bridge", 3, 10, 12), p("sumo-squat", 3, 12, 15, note = "Pause 1 s at the bottom"),
                        p("fire-hydrant", 3, 12, 15, rest = 30), p("towel-hamstring-curl", 3, 8, 10, rest = 45)),
                    core(p("reverse-crunch", 2, 10, 12, rest = 30)), cooldown,
                )),
            )),
            Phase(2, "Peak", 8, "Slow lowering, pauses and 1½ reps make bodyweight feel heavy. Stay here as long as you keep progressing.", listOf(
                Session("A", "Peak A", "Single-leg thrust · Bulgarian · pump", listOf(
                    warmup("glute-bridge"),
                    main(p("single-leg-hip-thrust", 3, 10, 12, note = "1 s pause at the top"), p("bulgarian-split-squat", 3, 10, 12, note = "1½ reps"),
                        p("frog-pump", 3, 20, 25, rest = 30), p("clamshell", 2, 15, 20, rest = 30)),
                    core(p("plank-shoulder-tap", 2, 10, 12, rest = 30)), cooldown,
                )),
                Session("B", "Peak B", "Lunge · hinge · thrust burnout", listOf(
                    warmup("step-jacks"),
                    main(p("reverse-lunge", 3, 10, 12, note = "3 s down"), p("single-leg-rdl", 3, 10, 12, rest = 45),
                        p("hip-thrust", 3, 15, 20, note = "Pulse at the top for the last 5"), p("donkey-kick", 2, 15, 20, rest = 30)),
                    core(t("side-plank", 2, 30, rest = 30)), cooldown,
                )),
                Session("C", "Peak C", "Curtsy · hamstrings · single-leg bridge", listOf(
                    warmup("glute-bridge"),
                    main(p("curtsy-lunge", 3, 10, 12), p("towel-hamstring-curl", 3, 10, 12, rest = 45),
                        p("single-leg-glute-bridge", 3, 12, 15), p("fire-hydrant", 2, 15, 20, rest = 30)),
                    core(t("hollow-hold", 2, 20, rest = 30)), cooldown,
                )),
            )),
        ),
    )

    val quick15 = TrainingProgram(
        id = "quick-15",
        name = "Quick 15",
        tagline = "Short sessions for busy days",
        description = "About 15 minutes, four times a week: two lower-body and two upper-body days with short rests. Small, regular wins.",
        level = "Beginner → intermediate",
        sessionsPerWeek = 4,
        focus = listOf("Full body", "Short"),
        equipment = "A chair and a sturdy table",
        phases = listOf(
            Phase(0, "Start", 4, "Two sets, short rests. Move with control and keep a little in reserve.", listOf(
                Session("A", "Quick lower", "Squat · bridge · side glutes · core", listOf(
                    quickWarmup,
                    main(p("box-squat", 2, 10, 12, rest = 30), p("glute-bridge", 2, 12, 15, rest = 30), p("side-lying-leg-raise", 2, 12, 15, rest = 20)),
                    core(p("dead-bug", 2, 6, 8, rest = 30)), quickCooldown,
                )),
                Session("B", "Quick upper", "Push · row · posture · core", listOf(
                    quickWarmup,
                    main(p("wall-pushup", 2, 8, 12, rest = 30), p("doorway-row", 2, 10, 12, rest = 30), p("prone-ytw", 2, 6, 8, rest = 30)),
                    core(t("knee-plank", 2, 20, rest = 30)), quickCooldown,
                )),
            )),
            Phase(1, "Steady", 8, "Three sets on the big moves and harder versions. Still about 15 minutes.", listOf(
                Session("A", "Quick lower", "Squat · single-leg bridge · hydrant · core", listOf(
                    quickWarmup,
                    main(p("bodyweight-squat", 3, 12, 15, rest = 30), p("single-leg-glute-bridge", 2, 8, 10, rest = 30),
                        p("fire-hydrant", 2, 12, 15, rest = 20)),
                    core(t("plank", 2, 30, rest = 30)), quickCooldown,
                )),
                Session("B", "Quick upper", "Push · row · dip · core", listOf(
                    quickWarmup,
                    main(p("incline-pushup", 3, 8, 12, rest = 30), p("table-row", 3, 8, 10, rest = 30), p("bench-dip", 2, 8, 10, rest = 30)),
                    core(t("side-plank-knees", 2, 20, rest = 20), p("reverse-crunch", 2, 10, 12, rest = 20)), quickCooldown,
                )),
            )),
        ),
    )

    val corePosture = TrainingProgram(
        id = "core-posture",
        name = "Core & Posture",
        tagline = "A strong middle and a tall back",
        description = "Deep core (the muscles that pull your waist in), side core and the upper back that holds your shoulders back. About 20 minutes. Spot-reducing fat isn't possible, but this changes how you carry yourself.",
        level = "Beginner → intermediate",
        sessionsPerWeek = 3,
        focus = listOf("Deep core", "Waist", "Posture"),
        equipment = "A doorframe and a sturdy table",
        phases = listOf(
            Phase(0, "Base", 4, "Breathe out on the effort, ribs down, lower back gently pressed to the floor.", listOf(
                Session("A", "Deep core + back", "Dead bug · vacuum · Y-T-W · bird dog", listOf(
                    coreWarmup,
                    main(p("prone-ytw", 2, 6, 8, rest = 45), p("glute-bridge", 2, 12, 15, rest = 45)),
                    core(p("dead-bug", 2, 6, 8, rest = 45), t("stomach-vacuum", 2, 15, rest = 30), p("bird-dog", 2, 6, 8, rest = 30)),
                    stretchCooldown,
                )),
                Session("B", "Planks + posture", "Planks · snow angels · rows", listOf(
                    coreWarmup,
                    main(p("reverse-snow-angel", 2, 8, 10, rest = 45), p("doorway-row", 2, 10, 12, rest = 45)),
                    core(t("knee-plank", 2, 20, rest = 30), t("side-plank-knees", 2, 15, rest = 30), p("reverse-crunch", 2, 8, 10, rest = 30)),
                    stretchCooldown,
                )),
            )),
            Phase(1, "Strong", 6, "Longer holds and harder levers. Quality over time: stop a hold when your form slips.", listOf(
                Session("A", "Deep core + back", "Dead bug · hollow · Y-T-W · bird dog", listOf(
                    coreWarmup,
                    main(p("prone-ytw", 3, 8, 10, rest = 45), p("single-leg-glute-bridge", 2, 8, 10, rest = 45)),
                    core(p("dead-bug", 3, 8, 10, rest = 30), t("hollow-hold", 3, 20, rest = 30), p("bird-dog", 3, 8, 10, rest = 30),
                        t("stomach-vacuum", 2, 20, rest = 20)),
                    stretchCooldown,
                )),
                Session("B", "Planks + posture", "Planks · taps · rows", listOf(
                    coreWarmup,
                    main(p("table-row", 3, 8, 10), p("reverse-snow-angel", 2, 10, 12, rest = 45)),
                    core(t("plank", 3, 30, rest = 30), t("side-plank", 2, 25, rest = 30), p("plank-shoulder-tap", 2, 10, 12, rest = 30),
                        p("reverse-crunch", 2, 10, 12, rest = 30)),
                    stretchCooldown,
                )),
            )),
        ),
    )

    val gentleStart = TrainingProgram(
        id = "gentle-start",
        name = "Gentle Start",
        tagline = "Low impact, kind to your joints",
        description = "No jumping and nothing fast: chair, wall and floor moves, about 20 minutes. Great for a first month back, after a break, or on tired weeks. When it feels easy, switch to Feminine Shape.",
        level = "Complete beginner",
        sessionsPerWeek = 3,
        focus = listOf("Low impact", "Joints", "Habit"),
        equipment = "A chair, a wall and a low step",
        phases = listOf(
            Phase(0, "Easy", 3, "Two easy sets. You should finish feeling like you could do more.", listOf(
                Session("A", "Easy A", "Chair squat · wall push · bridge · core", listOf(
                    gentleWarmup,
                    main(p("box-squat", 2, 8, 10, note = "Sit all the way down, then stand"), p("wall-pushup", 2, 8, 10),
                        p("glute-bridge", 2, 10, 12), p("side-lying-leg-raise", 2, 10, 12, rest = 30)),
                    core(p("dead-bug", 2, 5, 6, rest = 45)), cooldown,
                )),
                Session("B", "Easy B", "Step-up · row · clamshell · core", listOf(
                    gentleWarmup,
                    main(p("step-up", 2, 6, 8, note = "Low step; hold a wall"), p("doorway-row", 2, 8, 10),
                        p("clamshell", 2, 10, 12, rest = 30), p("good-morning", 2, 8, 10, rest = 45)),
                    core(p("bird-dog", 2, 5, 6, rest = 45)), cooldown,
                )),
            )),
            Phase(1, "Steady", 5, "Three sets on the main moves. Ready for more? Switch to Feminine Shape.", listOf(
                Session("A", "Steady A", "Chair squat · wall push · bridge march · core", listOf(
                    gentleWarmup,
                    main(p("box-squat", 3, 10, 12), p("wall-pushup", 3, 10, 12), p("glute-bridge-march", 2, 8, 10),
                        p("side-lying-leg-raise", 2, 12, 15, rest = 30)),
                    core(t("knee-plank", 2, 15, rest = 45)), cooldown,
                )),
                Session("B", "Steady B", "Step-up · row · clamshell · core", listOf(
                    gentleWarmup,
                    main(p("step-up", 3, 8, 10), p("doorway-row", 3, 10, 12), p("clamshell", 2, 12, 15, rest = 30), p("good-morning", 3, 10, 12, rest = 45)),
                    core(p("dead-bug", 2, 6, 8, rest = 45)), cooldown,
                )),
            )),
        ),
    )

    val fullBody = TrainingProgram(
        id = "full-body",
        name = "Full-Body Strength",
        tagline = "Strong everywhere, toned arms and back",
        description = "Balanced lower body, push and pull three times a week, so your arms, shoulders and back get as much love as your legs. About 35 minutes.",
        level = "Beginner → advanced",
        sessionsPerWeek = 3,
        focus = listOf("Full body", "Arms & back", "Strength"),
        equipment = "A chair, a doorframe and a sturdy table",
        phases = listOf(
            Phase(0, "Base", 4, "Learn every pattern: squat, hinge, push, pull. Three sets, 2–3 reps in reserve.", listOf(
                Session("A", "Base A", "Squat · push · bridge · row", listOf(
                    warmup("step-jacks"),
                    main(p("bodyweight-squat", 3, 10, 12), p("incline-pushup", 3, 8, 10), p("glute-bridge", 3, 12, 15), p("doorway-row", 3, 10, 12)),
                    core(t("plank", 2, 20, rest = 30)), cooldown,
                )),
                Session("B", "Base B", "Split squat · row · hinge · dip", listOf(
                    warmup("step-jacks"),
                    main(p("split-squat", 3, 8, 8), p("table-row", 3, 8, 10), p("good-morning", 3, 10, 12, rest = 45), p("bench-dip", 2, 8, 10)),
                    core(t("side-plank-knees", 2, 20, rest = 30)), cooldown,
                )),
            )),
            Phase(1, "Strength", 8, "Harder versions and an extra pull. Add reps before you move up a level.", listOf(
                Session("A", "Strength A", "Bulgarian · push · thrust · row", listOf(
                    warmup("step-jacks"),
                    main(p("bulgarian-split-squat", 3, 8, 10), p("knee-pushup", 3, 8, 12), p("hip-thrust", 3, 12, 15),
                        p("table-row", 3, 8, 12), p("reverse-snow-angel", 2, 10, 12, rest = 45)),
                    core(t("hollow-hold", 2, 20, rest = 30)), cooldown,
                )),
                Session("B", "Strength B", "Lunge · single-leg hinge · dip · row", listOf(
                    warmup("step-jacks"),
                    main(p("reverse-lunge", 3, 10, 10), p("single-leg-rdl", 3, 8, 10), p("bench-dip", 3, 8, 12), p("doorway-row", 3, 12, 15)),
                    core(p("plank-shoulder-tap", 2, 10, 12, rest = 30)), cooldown,
                )),
            )),
            Phase(2, "Advanced", 8, "Full push-ups, single-leg thrusts and slow tempo. Repeat as long as you keep progressing.", listOf(
                Session("A", "Advanced A", "Bulgarian · push-up · single-leg thrust · row", listOf(
                    warmup("glute-bridge"),
                    main(p("bulgarian-split-squat", 3, 10, 12, note = "3 s down"), p("pushup", 3, 6, 10), p("single-leg-hip-thrust", 3, 10, 12),
                        p("table-row", 3, 10, 12)),
                    core(t("side-plank", 2, 30, rest = 30)), cooldown,
                )),
                Session("B", "Advanced B", "Side lunge · hamstrings · back · dip", listOf(
                    warmup("step-jacks"),
                    main(p("lateral-lunge", 3, 10, 10), p("towel-hamstring-curl", 3, 10, 12), p("reverse-snow-angel", 3, 10, 12, rest = 45),
                        p("prone-ytw", 2, 10, 10, rest = 45), p("bench-dip", 3, 10, 12)),
                    core(t("hollow-hold", 2, 30, rest = 30)), cooldown,
                )),
            )),
        ),
    )

    val all: List<TrainingProgram> = listOf(starter15, Program.program, gluteBuilder, quick15, corePosture, gentleStart, fullBody)

    fun byId(id: String?): TrainingProgram = all.firstOrNull { it.id == id } ?: Program.program
}
