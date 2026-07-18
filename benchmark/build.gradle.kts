plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.aatricks.easyreader.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    flavorDimensions.add("version")
    productFlavors {
        create("standard") {
            dimension = "version"
        }
        create("ai") {
            dimension = "version"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val verifyBenchmarkTestDiscovery by tasks.registering {
    group = "verification"
    description = "Fails loudly if the benchmark module contains zero @Test methods."
    inputs.dir(layout.projectDirectory.dir("src"))
    doLast {
        var testCount = 0
        inputs.files.files.filter { it.isFile && it.extension == "kt" }.forEach { sourceFile ->
            val sourceText = sourceFile.readText()
            testCount += Regex("""(?m)^\s*@Test\b""").findAll(sourceText).count()
        }
        if (testCount == 0) {
            throw GradleException("Benchmark discovery error: Zero @Test benchmark/profile methods found in :benchmark module!")
        }
        logger.lifecycle("Verified benchmark test discovery: found $testCount @Test methods.")
    }
}

tasks.matching { it.name.startsWith("compile") }.configureEach {
    dependsOn(verifyBenchmarkTestDiscovery)
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
