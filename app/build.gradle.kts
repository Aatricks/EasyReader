import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

detekt {
    toolVersion = libs.versions.detekt.get()
    source.setFrom("src/main/java", "src/standard/java", "src/ai/java")
    baseline = file("detekt-baseline.xml")
    parallel = true
    buildUponDefaultConfig = true
    autoCorrect = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
    jvmTarget = "17"
}

android {
    namespace = "io.aatricks.easyreader"
    compileSdk = 37

    defaultConfig {
        // Keep the legacy applicationId so existing installs continue to update in-place.
        applicationId = "io.aatricks.novelscraper"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "0.5.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val properties = Properties().apply {
                    load(keystorePropertiesFile.inputStream())
                }
                storeFile = properties.getProperty("storeFile")?.let { file(it) }
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            } else {
                // If the file is missing, we don't set the properties.
                // The build will fail only when release flavor tasks are called
                // (for example, assembleStandardRelease or assembleAiRelease),
                // which is the desired behavior for PRs that shouldn't build release.
                println("Warning: keystore.properties not found. Release builds will fail to sign.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    flavorDimensions.add("version")
    productFlavors {
        create("standard") {
            dimension = "version"
            isDefault = true
        }
        create("ai") {
            dimension = "version"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("standard") {
            java.directories.add("src/standard/java")
        }
        getByName("ai") {
            java.directories.add("src/ai/java")
        }
        getByName("debug") {
            assets.directories.add("schemas")
        }
        getByName("test") {
            java.directories.add("src/test/java")
            // Opt-in: pass -PrunBenchmarks=true to include the slow benchmark suite
            // in the standard test task. Default off to keep PR CI fast.
            if (project.findProperty("runBenchmarks") == "true") {
                java.directories.add("src/benchmark/java")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed", "standardOut", "standardError")
                showStandardStreams = true
            }
            if (project.findProperty("runBenchmarks") != "true") {
                it.exclude("**/*BenchmarkTest*")
            }
        }
    }

    // Convenience alias: ./gradlew :app:benchmark runs the standardDebug unit-test task
    // with the benchmark filter and the runBenchmarks property turned on, so the slow
    // suite under src/benchmark/java is compiled and executed.
    tasks.register("benchmark") {
        group = "verification"
        description = "Run the manual benchmark suite (slow). Use ./gradlew :app:benchmark."
        dependsOn(":app:testStandardDebugUnitTest")
        doFirst {
            if (project.findProperty("runBenchmarks") != "true") {
                throw GradleException(
                    "Pass -PrunBenchmarks=true to enable the benchmark suite. " +
                        "Example: ./gradlew :app:benchmark -PrunBenchmarks=true"
                )
            }
        }
    }

    lint {
        // Pin the current set of lint findings so new regressions are visible in CI.
        // Regenerate with: ./gradlew :app:updateLintBaseline
        baseline = file("lint-baseline.xml")
        // Keep the build green when only existing baselined findings remain.
        checkReleaseBuilds = true
        abortOnError = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes.add("org/bouncycastle/pqc/crypto/**/*.properties")
            excludes.add("com/itextpdf/io/font/cmap/*")
            excludes.add("com/itextpdf/hyph/*")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt 2.60's generated code references com.google.errorprone.annotations (now compileOnly in
    // Dagger), so the generated-Java compile needs these annotations on the classpath.
    compileOnly("com.google.errorprone:error_prone_annotations:2.50.0")
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)

    // Navigation
    implementation(libs.navigation.compose)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // llmedge AI Library
    "aiImplementation"(libs.llmedge)
    
    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    // Web Scraping - JSoup
    implementation(libs.jsoup)
    
    // Image Loading - Coil 3
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    
    // PDF Parsing - iText7
    implementation(libs.itext7.core) {
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.bouncycastle.bcprov.jdk15to18)
    implementation(libs.bouncycastle.bcpkix.jdk15to18)
    implementation(libs.bouncycastle.bcutil.jdk15to18)
    
    // Networking - OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    // Ktor's OkHttp engine pulls okhttp-sse in transitively at an older version than the rest
    // of the okhttp family; declaring it explicitly forces it to resolve at the same version so
    // its internals (e.g. RealEventSource) stay binary-compatible with okhttp itself.
    implementation(libs.okhttp.sse)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.inline)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
