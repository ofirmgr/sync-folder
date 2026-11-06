import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
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

val externalKeystorePropsPath: String =
    System.getenv("SYNCFOLDER_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/Library/Application Support/SyncFolder/keys/keystore.properties"

val keystoreProps = Properties().also { props ->
    val projectFile = rootProject.file("keystore.properties")
    val externalFile = file(externalKeystorePropsPath)
    when {
        projectFile.exists() -> props.load(projectFile.inputStream())
        externalFile.exists() -> props.load(externalFile.inputStream())
    }
}

fun quotedBuildConfigValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val legalSite = "https://ofirmgr.github.io/sync-folder"
val legalUrls = mapOf(
    "PRIVACY_POLICY_URL" to localProps.getProperty("privacy_policy_url", "$legalSite/privacy/"),
    "TERMS_URL" to localProps.getProperty("terms_url", "$legalSite/terms/"),
    "ACCESSIBILITY_URL" to localProps.getProperty("accessibility_url", "$legalSite/accessibility/")
)

android {
    namespace = "com.ofir.syncfolder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ofir.syncfolder"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"

        val serverClientId = localProps.getProperty("server_client_id", "")
        buildConfigField("String", "SERVER_CLIENT_ID", quotedBuildConfigValue(serverClientId))
        legalUrls.forEach { (name, url) -> buildConfigField("String", name, quotedBuildConfigValue(url)) }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standalone") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
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
            for ((name, url) in legalUrls) {
                val uri = runCatching { URI(url) }.getOrNull()
                if (uri == null || uri.scheme != "https" || uri.host == null || uri.userInfo != null) {
                    add("a public HTTPS URL for $name")
                }
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

tasks.matching { it.name == "bundlePlayRelease" }.configureEach {
    dependsOn("validatePlayRelease")
}

tasks.register("verifyPlayReleaseUrls") {
    group = "verification"
    description = "Checks that the public legal pages in the Play bundle are reachable."
    dependsOn("validatePlayRelease")

    doLast {
        val failures = legalUrls.mapNotNull { (name, url) ->
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try {
                val status = connection.responseCode
                if (status in 200..299 && connection.contentType?.startsWith("text/html") == true) null
                else "$name ($url): HTTP $status, content type ${connection.contentType}"
            } catch (e: IOException) {
                "$name ($url): ${e.message}"
            } finally {
                connection.disconnect()
            }
        }
        check(failures.isEmpty()) { "Public legal pages are unavailable:\n${failures.joinToString("\n")}" }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
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
