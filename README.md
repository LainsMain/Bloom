# Bloom

A personal, local-first Android app for steady, healthy fat loss and a stronger, shapelier body. It combines a calorie tracker with an AI coach, a run-walk tracker with GPS maps, a guided bodyweight strength program, and a weekly review that adapts the plan to how your week really went.

Everything is stored on the phone. There's no account and no server.

## Install

1. On your phone, open the [latest release](https://github.com/LainsMain/Bloom/releases/latest) and download `bloom-X.Y.Z.apk`.
2. Open the file. Android asks you to allow installs from your browser or file manager once.
3. Open Bloom and set up your profile.

Bloom checks this repository for a newer release when it opens (you can turn this off in Settings). When there's one, it downloads the APK, checks GitHub's SHA-256 digest, confirms it's the same app with a higher version signed with the same key, and opens Android's installer. The first time, Android asks you to allow Bloom to install updates.

Requires Android 12 or newer (arm64 phones).

## What's in it

- **Food tracking:** search your own foods, a built-in offline food database (USDA), and Open Food Facts. Barcode scanner (live camera or from a photo); unknown products can be added by hand or looked up by the AI. Recipes with cooked weight, saved meals, quick add, a diary with day completeness.
- **AI coach (DeepSeek, optional):** describe or photograph a meal and it's logged with Undo; barcodes in photos are read on the phone. It can save recipes and regular combos, copy meals, log workouts, runs and measurements done outside the app, read your exact sets and reps to coach progression, look back over 120 days of nutrition, compare progress photos when you ask, and schedule a reminder. You can give it notes about yourself in Settings ("About you, for your coach"), e.g. hormone therapy, an injury or a diet, so it doesn't assume. Changes to targets, goals or training are only applied when you tap Accept. Uses your own DeepSeek API key, stored encrypted with the Android Keystore.
- **Strength:** a 4-phase home bodyweight program (glutes, legs, deep core, posture), 51 exercises with embedded demo videos and easier/harder variations, and a guided player with timers, voice cues and a big pause button.
- **Running:** a 27-run beginner run-walk program or your own interval plan (whichever you choose is shown on Today on run days), GPS tracking with a live map, splits and pace.
- **Health Connect:** reads steps, weigh-ins and other apps' workouts; writes your runs and workouts. Runs are never counted twice, and exercise never adds calories to your food budget.
- **Weekly review:** marks which days were logged completely, checks in on energy, hunger, sleep and soreness, then estimates your real energy expenditure from intake and the weight trend. The new target changes by at most 150 kcal a week and never goes below a safe minimum. The AI explains the numbers and can suggest training changes.
- **Progress:** weight trend with goal projection (period days marked when cycle tracking is on), calories vs target, waist and hips, training per week, run distance, progress photos.
- **Cycle tracking (optional):** log period days, flow and symptoms on a calendar; see cycle day, phase and the expected next period on Today. The weekly review counts a week for less when period water weight sits at either end of it, and the coach takes the cycle into account before reacting to the scale.
- **Reminders** for the weekly review, weigh-ins, meal logging and training days.
- **Privacy:** optional app lock (fingerprint, face or screen lock) and password-encrypted backups (AES-256-GCM) to a file you choose.

Bloom gives general guidance, not medical advice.

## Build

Use JDK 17 and the Android SDK (API 37).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug   # debug APK: app/build/outputs/apk/debug/
./gradlew :app:connectedDebugAndroidTest              # database and migration tests on a device
```

The debug build installs next to the release build (`be.lain.bloom.debug`), so test data never mixes with real data.

## Releases

```sh
scripts/release.sh 1.1.0 notes.md
```

The script bumps the version in `gradle.properties` (`versionCode = major × 10000 + minor × 100 + patch`), runs the unit tests, builds an R8-minified arm64 APK, checks its signature, tags the commit and publishes the GitHub release that the in-app updater reads.

Release APKs are signed with the key at `~/.config/bloom/release.jks`; its settings are in `~/.config/bloom/signing.properties` (or the file named by `BLOOM_SIGNING_PROPERTIES`). **Back up both.** Every future update must be signed with the same key, or phones will refuse to install it over the existing app.

## Structure

- `domain/`: pure Kotlin logic with unit tests: energy targets, weight trend, the adaptive weekly engine (`Adaptive.kt`), workout and interval engines.
- `data/`: Room database (schemas in `app/schemas`), repositories, food sources, DeepSeek client and tools, Health Connect sync, backup, updater.
- `feature/`: Compose screens (Material 3 Expressive).
- `service/`: workout and run foreground services, reminders.
- `scripts/`: `build_foods.py` (food database), `verify_videos.py` (checks demo video IDs), `release.sh`.

## Data and credits

- Generic foods: [USDA FoodData Central](https://fdc.nal.usda.gov/) SR Legacy (public domain).
- Packaged foods: [Open Food Facts](https://world.openfoodfacts.org/) (ODbL), queried online.
- Maps: [OpenFreeMap](https://openfreemap.org/) tiles, © [OpenMapTiles](https://openmaptiles.org/) © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, rendered with [MapLibre](https://maplibre.org/).
- Exercise demos are YouTube videos played with the official embedded player; they belong to their creators. Any video can be replaced from the exercise screen.
