import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Единственный источник версии — файл VERSION в корне репозитория
 * (например 0.1.2 или 0.2.0-beta.1). Из него берутся versionName, versionCode,
 * версия десктопного пакета и константа AppVersion для кода приложения
 * (User-Agent по умолчанию, «О приложении»).
 */
val appVersion: String = rootProject.file("VERSION").readText().trim()
    .also { require(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(it)) { "VERSION: ожидается X.Y.Z[-pre], получено '$it'" } }

/** X.Y.Z без суффикса. */
val appVersionCore: String = appVersion.substringBefore('-')

/**
 * versionCode растёт вместе с версией: X·1 000 000 + Y·10 000 + Z·100 + стадия.
 * Предрелиз (-alpha, -beta.2…) получает 0–98 по номеру в суффиксе, релиз — 99,
 * поэтому 0.2.0-beta.3 < 0.2.0 < 0.2.1-alpha.
 */
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

/** Kotlin-константа с версией для общего кода. */
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
                // M3 Expressive: морфинг фигур из официальной библиотеки graphics-shapes
                implementation("androidx.graphics:graphics-shapes:1.1.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                // QR: ZXing из Maven Central (Google Maven недоступен),
                // камера — нативный camera2, без лишних зависимостей
                implementation(libs.zxing.core)
                // Ядро собирается Scripts/build-xray-android.sh. Пока AAR нет,
                // проект всё равно компилируется — подключение честно откажет.
                // Единый gomobile-AAR: Xray + sing-box libbox в одном go.Seq.
                // Старый xray.aar нельзя держать рядом — будет дубль go.Seq.
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
            // В CI ключ приходит секретами; локально релиз подписывается debug-ключом.
            val store = System.getenv("VEIL_KEYSTORE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("VEIL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VEIL_KEY_ALIAS")
                keyPassword = System.getenv("VEIL_KEY_PASSWORD")
            }
        }
    }

    // Сплиты по ABI включаются только для релиза: -PabiSplits.
    // Ядро собрано под arm64-v8a и x86_64, поэтому 32-битные сборки не делаем.
    splits {
        abi {
            isEnable = providers.gradleProperty("abiSplits").isPresent
            reset()
            include("arm64-v8a", "x86_64")
            // Универсальный APK содержит оба ядра (~130 МБ) и не нужен:
            // у всех реальных устройств arm64-v8a, x86_64 — только для эмулятора.
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            // Если задан релизный ключ, debug-сборка подписывается им же:
            // иначе её нельзя поставить поверх релизного APK.
            signingConfig = if (!System.getenv("VEIL_KEYSTORE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            // R8 вырезает неиспользуемый код (иконки Material, корутины, Compose)
            // и переименовывает классы: dex на порядок меньше.
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
            // Go-ядро (libgojni.so) занимает 61 МБ и не сжимается само по себе:
            // Android хранит .so в APK без сжатия. Legacy packaging упаковывает
            // его deflate (61 → ~21 МБ), а установщик распаковывает при установке.
            // Плата — чуть более долгая установка и место на диске, зато APK
            // для скачивания втрое меньше.
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
            // jpackage принимает только X.Y.Z, а DMG — ещё и мажор не ниже 1.
            packageVersion = appVersionCore.split('.').let { (major, minor, patch) ->
                "${maxOf(major.toInt(), 1)}.$minor.$patch"
            }
        }
    }
}
