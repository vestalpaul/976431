import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val versionPropsText = providers
    .fileContents(rootProject.layout.projectDirectory.file("version.properties"))
    .asText
    .get()
val versionProps = Properties().apply {
    load(versionPropsText.reader())
}
val vMajor = versionProps.getProperty("VERSION_MAJOR")?.trim()?.toInt()
    ?: error("Missing VERSION_MAJOR in version.properties")
val vMinor = versionProps.getProperty("VERSION_MINOR")?.trim()?.toInt()
    ?: error("Missing VERSION_MINOR in version.properties")
val vPatch = versionProps.getProperty("VERSION_PATCH")?.trim()?.toInt()
    ?: error("Missing VERSION_PATCH in version.properties")
val computedVersionCode = versionProps.getProperty("VERSION_CODE")?.trim()?.toInt()
    ?: error("Missing VERSION_CODE in version.properties")
val computedVersionName = if (vPatch == 0) "$vMajor.$vMinor" else "$vMajor.$vMinor.$vPatch"

val keystorePropsText = providers
    .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText
    .orNull
val keystoreProps = Properties().apply {
    if (!keystorePropsText.isNullOrBlank()) {
        load(keystorePropsText.reader())
    }
}
val envStoreFile = providers.environmentVariable("SCHWABQUANT_STORE_FILE").orNull
val envStorePassword = providers.environmentVariable("SCHWABQUANT_STORE_PASSWORD").orNull
val envKeyAlias = providers.environmentVariable("SCHWABQUANT_KEY_ALIAS").orNull
val envKeyPassword = providers.environmentVariable("SCHWABQUANT_KEY_PASSWORD").orNull

val signingStorePath = keystoreProps.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }
    ?: envStoreFile?.trim()?.takeIf { it.isNotEmpty() }
val signingStorePassword = keystoreProps.getProperty("storePassword")?.trim()?.takeIf { it.isNotEmpty() }
    ?: envStorePassword?.trim()?.takeIf { it.isNotEmpty() }
val signingKeyAlias = keystoreProps.getProperty("keyAlias")?.trim()?.takeIf { it.isNotEmpty() }
    ?: envKeyAlias?.trim()?.takeIf { it.isNotEmpty() }
val signingKeyPassword = keystoreProps.getProperty("keyPassword")?.trim()?.takeIf { it.isNotEmpty() }
    ?: envKeyPassword?.trim()?.takeIf { it.isNotEmpty() }

val resolvedSigningKeystoreFile = signingStorePath?.let { raw ->
    val f = File(raw)
    if (f.isAbsolute) f else rootProject.layout.projectDirectory.file(raw).asFile
}?.takeIf { it.exists() }

val hasReleaseSigning = resolvedSigningKeystoreFile != null &&
    signingStorePassword != null &&
    signingKeyAlias != null &&
    signingKeyPassword != null

base {
    archivesName.set("SchwabQuant-V$computedVersionName")
}

android {
    namespace = "com.schwabquant.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.schwabquant.app"
        minSdk = 28
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = computedVersionName

        buildConfigField("String", "APP_VERSION_LABEL", "\"V$computedVersionName\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = resolvedSigningKeystoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.tink.android)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
