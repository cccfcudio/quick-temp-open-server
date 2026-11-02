plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.openshelf"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.openshelf"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.1.12"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libcloudflared.so")
        }
    }
}

base {
    archivesName.set("quick-temp-open-server-0.1.12")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}

val cloudflaredBinary =
    layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libcloudflared.so")
val cloudflaredBuildInfo =
    layout.projectDirectory.file("src/main/assets/cloudflared-build.txt")

tasks.register<Exec>("buildCloudflared") {
    group = "build"
    description = "Compile libcloudflared.so for Android arm64 (Try Cloudflare)."
    inputs.file(rootProject.file("third_party/CLOUDFLARED_VERSION"))
    inputs.file(rootProject.file("third_party/android_dns.go"))
    inputs.file(rootProject.file("scripts/build-cloudflared-android.sh"))
    outputs.file(cloudflaredBinary)
    outputs.file(cloudflaredBuildInfo)
    val force = providers.gradleProperty("rebuildCloudflared").isPresent
    onlyIf { force || !cloudflaredBinary.asFile.exists() }
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-cloudflared-android.sh")
}

tasks.register("ensureCloudflared") {
    group = "verification"
    description = "Ensure libcloudflared.so exists before packaging the APK."
    dependsOn("buildCloudflared")
    doLast {
        check(cloudflaredBinary.asFile.isFile) {
            "libcloudflared.so is missing after buildCloudflared. Install Go 1.27+ and run " +
                "scripts/build-cloudflared-android.sh, or retry with a network for GOTOOLCHAIN=auto."
        }
    }
}

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn("ensureCloudflared")
    }
}
