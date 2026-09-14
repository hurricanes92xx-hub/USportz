plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.usportz.app"
    compileSdk = 35

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.usportz.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        fun prop(name: String, fallback: String = "") = project.findProperty(name)?.toString()?.trim()?.ifBlank { fallback } ?: fallback
        fun quote(value: String) = "\"" + value.replace("\"", "\\\"") + "\""

        val ncaaBase = prop("NCAA_API_BASE_URL", "https://ncaa-api.henrygd.me")
        val ncaaKey = prop("NCAA_API_KEY")
        val bdlKey = prop("BALLDONTLIE_API_KEY")
        val sportsDataverseUrl = prop("SPORTSDATAVERSE_URL")
        val pwhlLeagueStatUrl = prop("PWHL_LEAGUESTAT_URL")
        buildConfigField("String", "NCAA_API_BASE_URL", quote(ncaaBase))
        buildConfigField("String", "NCAA_API_KEY", quote(ncaaKey))
        buildConfigField("String", "BALLDONTLIE_API_KEY", quote(bdlKey))
        buildConfigField("String", "SPORTSDATAVERSE_URL", quote(sportsDataverseUrl))
        buildConfigField("String", "PWHL_LEAGUESTAT_URL", quote(pwhlLeagueStatUrl))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    testImplementation("junit:junit:4.13.2")
}
