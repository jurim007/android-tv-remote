import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun gitOutput(vararg args: String): String? =
    providers.exec {
        workingDir(rootDir)
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf(String::isNotEmpty)

val releaseTag = Regex("""v?\d+(?:\.\d+){1,2}(?:[-+][A-Za-z0-9.\-+]+)?""")
val workflowTag = providers.environmentVariable("GITHUB_REF_TYPE").orNull
    ?.takeIf { it == "tag" }
    ?.let { providers.environmentVariable("GITHUB_REF_NAME").orNull }
val releaseVersion = sequenceOf(
    { providers.gradleProperty("releaseVersion").orNull },
    { workflowTag },
    { gitOutput("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "--match", "[0-9]*") },
).mapNotNull { it()?.takeIf(releaseTag::matches)?.removePrefix("v") }.firstOrNull()

val githubLogin = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
val githubRemote = Regex(
    """(?:git@github\.com:|(?:https?|ssh)://(?:[^/@]+@)?(?:ssh\.)?github\.com(?::\d+)?/)([A-Za-z0-9-]+)/[^/]+/?""",
)
val githubAuthor = providers.environmentVariable("GITHUB_REPOSITORY_OWNER").orNull
    ?.takeIf(githubLogin::matches)
    ?: gitOutput("remote", "get-url", "origin")
        ?.let(githubRemote::matchEntire)?.groupValues?.get(1)?.takeIf(githubLogin::matches)
        .orEmpty()

android {
    namespace = "com.jurim007.androidtvremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jurim007.androidtvremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = releaseVersion ?: "1.0"
        buildConfigField("String", "GITHUB_AUTHOR", "\"$githubAuthor\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.caverock:androidsvg-aar:1.4")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

