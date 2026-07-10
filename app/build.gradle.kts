import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.math.sign

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.chaquopy)
}

private data class LocalBuildMeta(
    val buildDate: String,
    val buildNumber: Int,
)

private val isGithubActionsBuild = System.getenv("GITHUB_ACTIONS") == "true"
private val localBuildAbis = listOf("arm64-v8a")
private val githubActionsBuildAbis = listOf("arm64-v8a", "x86_64")

private fun shouldBumpLocalBuildNumber(taskNames: List<String>): Boolean {
    if (taskNames.isEmpty()) return false

    val buildTaskKeywords = listOf("assemble", "bundle", "package", "install", "build")
    return taskNames.any { taskName ->
        val normalizedTaskName = taskName.lowercase()
        buildTaskKeywords.any(normalizedTaskName::contains)
    }
}

private fun resolveLocalBuildMeta(
    versionFile: File,
    shouldBump: Boolean,
): LocalBuildMeta {
    val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) // yyyyMMdd
    val props = Properties()

    if (versionFile.exists()) {
        FileInputStream(versionFile).use(props::load)
    }

    val savedDate = props.getProperty("BUILD_DATE")
    val savedNumber = props.getProperty("BUILD_NUMBER")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val todayBaseNumber = if (savedDate == today) savedNumber else 0
    val resolvedNumber = when {
        shouldBump -> todayBaseNumber + 1
        todayBaseNumber > 0 -> todayBaseNumber
        else -> 1
    }

    if (shouldBump) {
        props.setProperty("BUILD_DATE", today)
        props.setProperty("BUILD_NUMBER", resolvedNumber.toString())
        versionFile.parentFile?.mkdirs()
        versionFile.outputStream().use { output ->
            props.store(output, "Auto-generated local build metadata.")
        }
    }

    return LocalBuildMeta(
        buildDate = today,
        buildNumber = resolvedNumber,
    )
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 36

    defaultConfig {
        applicationId = "lastchat.rikkafork.cocolal"
        minSdk = 31
        targetSdk = 36
        versionCode = ((System.currentTimeMillis() - 1577808000000) / 60000).toInt() // 基于 2020-01-01 00:00:00 UTC 的分钟数
        val baseVersionName = "1.5.0"
        versionName = if (isGithubActionsBuild) {
            baseVersionName
        } else {
            val localBuildMeta = resolveLocalBuildMeta(
                versionFile = rootProject.file("app/version.properties"),
                shouldBump = shouldBumpLocalBuildNumber(gradle.startParameter.taskNames),
            )
            val buildDate = localBuildMeta.buildDate.takeLast(4) // MMdd
            val buildNumber = localBuildMeta.buildNumber.toString().padStart(2, '0')
            "$baseVersionName-build.$buildDate.$buildNumber"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += if (isGithubActionsBuild) githubActionsBuildAbis else localBuildAbis
        }
    }

    // 让未被 mock 的 android.util.Log 等方法返回默认值而非抛异常，
    // 便于纯逻辑单测调用触及 Log 的工具函数（如 WebdavSync 的 addDirectoryToZip）。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("plus") {
            dimension = "channel"
            applicationIdSuffix = ".plus"
        }
        create("exp") {
            dimension = "channel"
            applicationIdSuffix = ".exp"
        }
    }

    splits {
        abi {
            // Chaquopy requires ndk.abiFilters, and AGP rejects overlapping
            // ndk/split ABI filters. Keep ndk filtering as the single ABI gate.
            isEnable = false
            reset()
            val buildAbis = if (isGithubActionsBuild) githubActionsBuildAbis else localBuildAbis
            include(*buildAbis.toTypedArray())
            isUniversalApk = isGithubActionsBuild
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            // Use release signing if configured, otherwise fall back to debug signing
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig?.storeFile != null && releaseSigningConfig.storeFile?.exists() == true) {
                signingConfig = releaseSigningConfig
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {

            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        create("baseline") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets {
        getByName("main") {
            assets.srcDir("../web-ui/build/client")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    applicationVariants.all {
        outputs.all {
            this as com.android.build.gradle.internal.api.ApkVariantOutputImpl

            // 用应用显示名作为文件名前缀：plus -> FLIT，exp -> FLIT_Dev
            val displayName = when (flavorName) {
                "exp" -> "FLIT_Dev"
                else -> "FLIT"
            }
            val buildTypeSuffix = buildType.name // release / debug
            val apkName = "${displayName}_${defaultConfig.versionName}-${buildTypeSuffix}.apk"

            outputFileName = apkName
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
    }
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

val webUiDir = rootProject.file("web-ui")
val webUiBuildDir = File(webUiDir, "build/client")

val buildWebUi by tasks.registering(Exec::class) {
    workingDir = webUiDir
    commandLine("npm", "run", "build")
    outputs.dir(webUiBuildDir)
}

tasks.named("preBuild") {
    dependsOn(buildWebUi)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.documentfile)
    implementation(libs.xz)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 2
    implementation(libs.androidx.navigation2)

    // Navigation 3
//    implementation(libs.androidx.navigation3.runtime)
//    implementation(libs.androidx.navigation3.ui)
//    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
//    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.jmdns)

    // pebble (template engine)
    implementation(libs.pebble)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Color Picker
    implementation(libs.compose.colorpicker)

    // WebDav
    implementation(libs.dav4jvm) {
        exclude(group = "org.ogce", module = "xpp3")
    }

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // Haze (glassmorphism blur for Compose, https://github.com/chrisbanes/haze)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // lucide icons
    implementation(libs.lucide.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // modules
    implementation(project(":ai"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":tts"))
    implementation(project(":common"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Glance (Widgets)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.material3)

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
