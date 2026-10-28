import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
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
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
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
                val xrayAar = file("libs/xray.aar")
                if (xrayAar.exists()) implementation(files(xrayAar))
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
        applicationId = "dev.local.veil"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

compose.desktop {
    application {
        mainClass = "dev.local.veil.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Veil"
            packageVersion = "1.0.0"
        }
    }
}
