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
        versionCode = 12
        versionName = "0.1.11"
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
    archivesName.set("quick-temp-open-server-0.1.11")
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

tasks.register("prepareCloudflared") {
    val binary = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libcloudflared.so")
    doLast {
        if (!binary.asFile.exists()) {
            logger.warn(
                "未找到 libcloudflared.so。APK 仍然可以建立，但 Try Cloudflare 會提示缺少二進位。" +
                    " 請先執行 scripts/build-cloudflared-android.sh。",
            )
        }
    }
}

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn("prepareCloudflared")
    }
}
