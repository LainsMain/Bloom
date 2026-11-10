import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "be.lain.bloom"
    compileSdk = 37

    defaultConfig {
        applicationId = "be.lain.bloom"
        minSdk = 31
        targetSdk = 37
        // Bumped by scripts/release.sh; versionCode = major*10000 + minor*100 + patch.
        versionName = providers.gradleProperty("bloom.versionName").get()
        versionCode = providers.gradleProperty("bloom.versionCode").get().toInt()
        buildConfigField("String", "UPDATE_REPO", "\"LainsMain/Bloom\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones are arm64; the emulator is x86_64. Published releases pass -Pbloom.abis=arm64-v8a
        // (the map engine's native code is ~13 MB per ABI).
        ndk { abiFilters += providers.gradleProperty("bloom.abis").getOrElse("arm64-v8a,x86_64").split(",") }
    }

    // Release signing lives outside the repo.
    val signingFile = file(
        System.getenv("BLOOM_SIGNING_PROPERTIES")
            ?: "${System.getProperty("user.home")}/.config/bloom/signing.properties",
    )
    if (signingFile.isFile) {
        val props = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs {
            create("releaseKey") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
        buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("releaseKey") } }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Exercise animation contact sheets: -Pbloom.preview=pushup,glute-bridge (or all).
        unitTests.all { it.systemProperty("bloom.preview", providers.gradleProperty("bloom.preview").getOrElse("")) }
    }
    sourceSets { getByName("androidTest").assets.directories.add("$projectDir/schemas") }
    lint {
        // Versions are pinned deliberately to a combination known to build here.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.youtube.player)
    // Filament's Java libraries declare kotlin-stdlib 2.4, newer than this toolchain (Hilt can't read
    // its metadata); they don't use it, so keep the project's own stdlib.
    implementation(libs.filament.android) { exclude(group = "org.jetbrains.kotlin") }
    implementation(libs.filament.gltfio) { exclude(group = "org.jetbrains.kotlin") }
    implementation(libs.play.services.location)
    implementation(libs.maplibre)
    implementation(libs.health.connect)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.androidx.biometric)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.androidx.exifinterface)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
