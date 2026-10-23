import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---- Pine SDK presence -------------------------------------------------------
// The SDK is a vendor bundle we cannot commit. When it is missing the app still
// builds (with a stub gateway that refuses real payments) so UI work can continue.
val pineSdkDir = rootProject.file("app/libs/pine-billing-sdk-android-maven")
val pineSdkPresent = pineSdkDir.resolve("com/pinelabs/billing").exists()
if (!pineSdkPresent) {
    logger.warn("⚠ Pine SDK bundle not found in app/libs/pine-billing-sdk-android-maven — building with STUB gateway (no real payments).")
}

// ---- Pine Application IDs (registered separately per environment) -------------
val pineUatAppId = "771aabd360b64cd59d11806bd88d87b4"
val pineProdAppId = ""   // TODO(pine): issued after UAT sign-off

// ---- Release signing ---------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bohemian.odoo.pos"
    compileSdk = 35

    defaultConfig {
        // Registered with Pine Labs — NEVER change.
        applicationId = "com.bohemian.odoo.pos"
        minSdk = 25          // E700 = PayDroid Android 7.1.2 (API 25)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "ODOO_BASE_URL", "\"https://zenzebra1.odoo.com\"")
        buildConfigField("String", "ODOO_DB", "\"zenzebra1\"")
        buildConfigField("boolean", "PINE_SDK_PRESENT", pineSdkPresent.toString())

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } // SDK ships only these
    }

    flavorDimensions += "env"
    productFlavors {
        create("uat") {
            dimension = "env"
            buildConfigField("String", "PINE_APPLICATION_ID", "\"$pineUatAppId\"")
            buildConfigField("String", "ENV_LABEL", "\"UAT\"")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "PINE_APPLICATION_ID", "\"$pineProdAppId\"")
            buildConfigField("String", "ENV_LABEL", "\"PROD\"")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(if (pineSdkPresent) "src/pineSdk/java" else "src/pineStub/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

// PROD must never ship without a Pine Application ID (empty id = MasterApp drops every request).
tasks.matching { it.name.startsWith("assembleProd") || it.name.startsWith("bundleProd") }.configureEach {
    doFirst {
        check(pineProdAppId.isNotBlank()) {
            "PROD Pine Application ID is empty. Set pineProdAppId in app/build.gradle.kts before building PROD."
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.security.crypto)

    if (pineSdkPresent) implementation(libs.pine.billing.sdk)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
