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
val pineProdAppId = "d0b34dd115e8487cb1e20b6706d90186"   // issued by Pine Labs 2026-10-27

// ---- Odoo deployment (the app's only network endpoint, docs/INFOSEC.md §2) ---------
// Set in gradle.properties; another deployment overrides with -PodooBaseUrl=… -PodooDb=…
// instead of editing code. Never plain http: the bearer API key travels in every request.
val odooBaseUrl = providers.gradleProperty("odooBaseUrl").get().trimEnd('/')
val odooDb = providers.gradleProperty("odooDb").get()
check(odooBaseUrl.startsWith("https://")) { "odooBaseUrl must be https:// (got $odooBaseUrl)" }
check(odooDb.isNotBlank()) { "odooDb is empty" }

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
        versionCode = 7     // +1 for every build sent to PAXSTORE (1 = first signed PROD APK, 2026-10-27; 2 was used by three different builds)
        versionName = "0.5.2" // above release-rc1's 0.2.0, so the newest build never looks older

        buildConfigField("String", "ODOO_BASE_URL", "\"$odooBaseUrl\"")
        buildConfigField("String", "ODOO_DB", "\"$odooDb\"")
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
                // Pine onboarding requires both. v1 is still written for minSdk 25: apksigner's
                // default verify skips v1 when minSdk >= 24, so check with --min-sdk-version 21.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Debug builds use the local debug key, NEVER the release key: a debuggable build
            // (which can enable the fake gateway) signed with the release key would install over
            // the production app on a store terminal as a valid "update".
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

// A PROD release must be signed with the one release key (Pine: same key for every update),
// with both v1 and v2 signatures (Pine onboarding requirement, enabled in signingConfigs above).
tasks.matching { it.name == "assembleProdRelease" || it.name == "bundleProdRelease" }.configureEach {
    doFirst {
        check(keystoreProps.isNotEmpty()) {
            "keystore.properties is missing: a PROD release must be signed with the release key (docs/DEPLOY_E700.md §2)."
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
