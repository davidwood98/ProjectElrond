import java.net.URLEncoder
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// API keys live in local.properties (gitignored) — never in source or VCS.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "ai.elrond"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.elrond"
        minSdk = 29 // Galaxy Tab S series; S Pen low-latency APIs
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "ai.elrond.HiltTestRunner"

        buildConfigField(
            "String",
            "ANTHROPIC_API_KEY",
            "\"${localProperties.getProperty("anthropic.apiKey", "")}\"",
        )

        // Outlook / Microsoft Graph OAuth (FA-11). All values come from local.properties
        // (gitignored) — the Azure app client id must never be committed, same posture as the
        // Anthropic key. Blank client id => the app runs with Outlook disabled (Events tab shows
        // a sign-in prompt that explains it isn't configured). See CLAUDE.md → Outlook OAuth setup.
        val outlookClientId = localProperties.getProperty("outlook.clientId", "")
        val outlookTenantId = localProperties.getProperty("outlook.tenantId", "common")
        // The package-signature hash (base64) from the Azure "Android" platform registration. The
        // manifest redirect path uses it raw; the MSAL redirect_uri uses the URL-encoded form.
        val outlookSignatureHash = localProperties.getProperty("outlook.signatureHash", "")
        val outlookRedirectUri = if (outlookSignatureHash.isNotBlank()) {
            "msauth://$applicationId/${URLEncoder.encode(outlookSignatureHash, "UTF-8")}"
        } else {
            ""
        }
        buildConfigField("String", "OUTLOOK_CLIENT_ID", "\"$outlookClientId\"")
        buildConfigField("String", "OUTLOOK_TENANT_ID", "\"$outlookTenantId\"")
        buildConfigField("String", "OUTLOOK_REDIRECT_URI", "\"$outlookRedirectUri\"")
        // The MSAL BrowserTabActivity intent-filter path (manifest). Raw signature hash; a harmless
        // placeholder keeps the manifest valid when Outlook isn't configured.
        manifestPlaceholders["msalRedirectPath"] =
            outlookSignatureHash.ifBlank { "PLACEHOLDER_SIGNATURE_HASH" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Ship native libraries uncompressed and page-aligned in the APK so they meet the
            // 16 KB page-size requirement (Android 15+ / Play from Nov 2025). NOTE: this aligns
            // how WE package each .so; it cannot realign LOAD segments baked into a third-party
            // prebuilt. com.google.mlkit:digital-ink-recognition's libdigitalink.so is built with
            // 4 KB segment alignment and is still flagged as unaligned — an unresolved upstream
            // issue (googlesamples/mlkit#938), so the Studio warning persists until Google rebuilds
            // it. Tracked as an accepted risk for release; not a development blocker. See CLAUDE.md.
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs Android resources/assets; default-value stubs keep the
            // existing pure-JVM tests unaffected.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        // Expose the exported Room schemas as assets so MigrationTestHelper can load them.
        // Robolectric reads the (debug) app variant's merged assets for JVM migration tests;
        // on-device instrumented tests read them from the androidTest APK assets. Debug-only,
        // so the schema JSONs never ship in a release build.
        getByName("debug").assets.srcDir("$projectDir/schemas")
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":aibackend"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Ink (low-latency stylus rendering)
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.input.motionprediction)

    // Data
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    // Dependency injection (Hilt)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Handwriting recognition
    implementation(libs.mlkit.digital.ink.recognition)
    implementation(libs.kotlinx.coroutines.play.services)

    // Outlook calendar (FA-11): MSAL for OAuth, Ktor for Microsoft Graph REST calls.
    // Ktor (over the heavy Graph SDK) keeps OutlookCalendarProvider unit-testable with
    // ktor-client-mock — the same HTTP-mock pattern :aibackend uses for Anthropic.
    implementation(libs.msal)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Unit tests (JVM + Robolectric for real Room DAO/migration coverage)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.ktor.client.mock) // mock Microsoft Graph HTTP — never hit the real API

    androidTestImplementation(libs.ktor.client.mock)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
