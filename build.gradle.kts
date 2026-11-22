plugins {
    id("com.android.library") version "9.4.1"
}

android {
    namespace = "ng.onshore.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The live test takes these from `-Pandroid.testInstrumentationRunnerArguments.*`.
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = false; unitTests.isReturnDefaultValues = false }
}

dependencies {
    // Nothing at runtime: a data-protection library should bring no one else's code into an app.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
