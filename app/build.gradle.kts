import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.aatricks.easyreader"
    compileSdk = 36

    defaultConfig {
        // Keep the legacy applicationId so existing installs continue to update in-place.
        applicationId = "io.aatricks.novelscraper"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "0.5.1"

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
                // The build will fail only when assembleRelease is called,
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("standard") {
            java.srcDirs("src/standard/java")
        }
        getByName("ai") {
            java.srcDirs("src/ai/java")
        }
        getByName("test") {
            java.srcDirs("src/test/java", "src/benchmark/java")
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
            // Exclude benchmarks from normal unit tests
            it.exclude("**/*BenchmarkTest.class")
        }
    }
}

tasks.register<Test>("benchmarkTest") {
    description = "Runs the benchmark tests."
    group = "verification"

    testClassesDirs = tasks.getByName<Test>("testStandardDebugUnitTest").testClassesDirs
    classpath = tasks.getByName<Test>("testStandardDebugUnitTest").classpath

    setTestNameIncludePatterns(listOf("*BenchmarkTest"))

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
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
    implementation(libs.hilt.navigation.compose)

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
    implementation(libs.itext7.core)
    
    // Networking - OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.inline)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}