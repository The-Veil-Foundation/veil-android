import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val appVersion: String = rootProject.file("VERSION").readText().trim()
    .also { require(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(it)) { "VERSION: ожидается X.Y.Z[-pre], получено '$it'" } }

val appVersionCore: String = appVersion.substringBefore('-')

val appVersionCode: Int = run {
    val (major, minor, patch) = appVersionCore.split('.').map { it.toInt() }
    val pre = appVersion.substringAfter('-', "")
    val stage = if (pre.isEmpty()) 99 else (Regex("""(\d+)$""").find(pre)?.value?.toInt() ?: 0).coerceIn(0, 98)
    major * 1_000_000 + minor * 10_000 + patch * 100 + stage
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val generateAppVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/appVersion/commonMain/kotlin")
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/local/veil/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.local.veil
            |
            |/** Сгенерировано из файла VERSION — не редактировать руками. */
            |object AppVersion {
            |    const val NAME: String = "$version"
            |
            |    /** User-Agent подписки по умолчанию. */
            |    const val USER_AGENT: String = "Veil/$version"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateAppVersion)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)

                implementation("androidx.graphics:graphics-shapes:1.1.0")

                implementation("androidx.collection:collection:1.5.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("dev.rikka.shizuku:api:13.1.5")
                implementation("dev.rikka.shizuku:provider:13.1.5")
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)

                implementation(libs.zxing.core)

                val coreAar = file("libs/veil-core.aar")
                val xrayAar = file("libs/xray.aar")
                when {
                    coreAar.exists() -> implementation(files(coreAar))
                    xrayAar.exists() -> implementation(files(xrayAar))
                }
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

android {
    namespace = "dev.local.veil"
    buildFeatures { aidl = true }
    sourceSets["main"].aidl.srcDir("src/androidMain/aidl")
    compileSdk = 36

    defaultConfig {
        applicationId = "org.veil.app"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        create("release") {

            val store = System.getenv("VEIL_KEYSTORE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("VEIL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VEIL_KEY_ALIAS")
                keyPassword = System.getenv("VEIL_KEY_PASSWORD")
            }
        }
    }

    splits {
        abi {
            isEnable = providers.gradleProperty("abiSplits").isPresent
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")

            isUniversalApk = false
        }
    }

    buildTypes {
        debug {

            signingConfig = if (!System.getenv("VEIL_KEYSTORE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (!System.getenv("VEIL_KEYSTORE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

        jniLibs {

            useLegacyPackaging = true
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.local.veil.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Veil"

            packageVersion = appVersionCore.split('.').let { (major, minor, patch) ->
                "${maxOf(major.toInt(), 1)}.$minor.$patch"
            }
        }
    }
}
