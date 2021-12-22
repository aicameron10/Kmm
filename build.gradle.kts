import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("kotlin-android-extensions")
}

kotlin {
    android()

    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }

    val iosTarget: (String, KotlinNativeTarget.() -> Unit) -> KotlinNativeTarget =
        if (System.getenv("SDK_NAME")?.startsWith("iphoneos") == true)
            ::iosArm64
        else
            ::iosX64

    iosTarget("ios") {
        binaries {
            framework {
                baseName = "kmmsharedmoduleFramework"
            }
        }
    }

    sourceSets {

        // CommonMain
        val commonMain by getting {
            dependencies {
                // Works as common dependency as well as the platform one
                implementation("org.jetbrains.kotlin:kotlin-stdlib-common")
                implementation(
                    "org.jetbrains.kotlinx:kotlinx-serialization-core:1.2.2"
                )
                implementation("io.ktor:ktor-client-core:${Versions.shared_module_ktor}")

                implementation("io.ktor:ktor-client-json:${Versions.shared_module_ktor}")

                implementation("io.ktor:ktor-client-serialization:${Versions.shared_module_ktor}")

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.shared_module_coroutine}")

                implementation("co.touchlab:stately-common:1.1.7")
                implementation("io.insert-koin:koin-core:3.0.2")
            }
        }

        // CommonTest
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        // AndroidMain
        val androidMain by getting {
            dependencies {
                implementation(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.shared_module_coroutine}"
                )
                implementation("io.ktor:ktor-client-android:${Versions.shared_module_ktor}")
            }
        }

        // AndroidTest
        val androidTest by getting {
            dependencies {
                implementation(Deps.testlib_junit_jupiter_api)
                implementation(Deps.testlib_junit_jupiter_engine)
            }
        }

        // iOSMain
        val iosMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-ios:${Versions.shared_module_ktor}")
            }
        }
    }
}

android {
    androidExtensions.isExperimental = true

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].java.srcDirs("src/androidMain/kotlin")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    sourceSets["test"].java.srcDirs("src/androidTest/kotlin")
    sourceSets["test"].res.srcDirs("src/androidTest/res")

    sourceSets["androidTest"].java.srcDirs("src/androidInstrumentationTest/kotlin")
    sourceSets["androidTest"].res.srcDirs("src/androidInstrumentationTest/res")
}

val packForXcode by tasks.creating(Sync::class) {
    val mode = System.getenv("CONFIGURATION") ?: "DEBUG"
    val framework = kotlin.targets.getByName<KotlinNativeTarget>("ios").binaries.getFramework(mode)
    val targetDir = File(buildDir, "xcode-frameworks")

    group = "build"
    dependsOn(framework.linkTask)
    inputs.property("mode", mode)

    from({ framework.outputDirectory })
    into(targetDir)
}

tasks.getByName("build").dependsOn(packForXcode)
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
