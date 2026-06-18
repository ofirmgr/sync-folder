import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

fun quotedBuildConfigValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.ofir.syncfolder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ofir.syncfolder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val serverClientId = localProps.getProperty("server_client_id", "")
        val privacyPolicyUrl = localProps.getProperty("privacy_policy_url", "")
        val termsUrl = localProps.getProperty("terms_url", "")
        val accessibilityUrl = localProps.getProperty("accessibility_url", "")
        buildConfigField("String", "SERVER_CLIENT_ID", quotedBuildConfigValue(serverClientId))
        buildConfigField("String", "PRIVACY_POLICY_URL", quotedBuildConfigValue(privacyPolicyUrl))
        buildConfigField("String", "TERMS_URL", quotedBuildConfigValue(termsUrl))
        buildConfigField("String", "ACCESSIBILITY_URL", quotedBuildConfigValue(accessibilityUrl))
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(
                    requireNotNull(keystoreProps.getProperty("storeFile")) {
                        "keystore.properties is missing storeFile"
                    }
                )
                storePassword = requireNotNull(keystoreProps.getProperty("storePassword")) {
                    "keystore.properties is missing storePassword"
                }
                keyAlias = requireNotNull(keystoreProps.getProperty("keyAlias")) {
                    "keystore.properties is missing keyAlias"
                }
                keyPassword = requireNotNull(keystoreProps.getProperty("keyPassword")) {
                    "keystore.properties is missing keyPassword"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

tasks.register("validatePlayRelease") {
    group = "verification"
    description = "Checks configuration required before uploading a release to Google Play."

    doLast {
        val missing = buildList {
            if (localProps.getProperty("server_client_id").isNullOrBlank()) {
                add("server_client_id in local.properties")
            }
            if (localProps.getProperty("privacy_policy_url").isNullOrBlank()) {
                add("privacy_policy_url in local.properties")
            }
            if (localProps.getProperty("terms_url").isNullOrBlank()) {
                add("terms_url in local.properties")
            }
            if (localProps.getProperty("accessibility_url").isNullOrBlank()) {
                add("accessibility_url in local.properties")
            }
            if (keystoreProps.isEmpty()) {
                add("keystore.properties")
            }
        }

        check(missing.isEmpty()) {
            "Google Play release configuration is incomplete:\n- ${missing.joinToString("\n- ")}"
        }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn("validatePlayRelease")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore)
    implementation(libs.workmanager)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
}
