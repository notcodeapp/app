import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing material lives outside the source tree and is never published.
val releaseSigningProperties = Properties().apply {
    val file = File(projectDir, "../../.keys/notcode-release.properties")
    if (file.isFile) FileInputStream(file).use { load(it) }
}
val releaseSigningReady = releaseSigningProperties.getProperty("storePassword") != null

android {
    namespace = "com.notcode.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.notcode.mobile"
        minSdk = 31
        targetSdk = 28
        versionCode = 20
        versionName = "1.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = File(projectDir, releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 stays off until keep rules for JGit, JSch and OkHttp are covered by tests.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

dependencies {
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.register("writeLocalTestClasspath") {
    dependsOn("compileDebugUnitTestKotlin", "bundleDebugClassesToRuntimeJar")
    doLast {
        val test = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get()
        layout.buildDirectory.file("local-test-classpath.txt").get().asFile.writeText(test.classpath.asPath)
    }
}
