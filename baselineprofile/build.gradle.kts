plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.usportz.app.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

baselineProfile {
    targetProjectPath = ":app"
}

dependencies {
    implementation(project(":app"))
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
    implementation("androidx.test:runner:1.6.2")
}
