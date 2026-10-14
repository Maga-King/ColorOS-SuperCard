plugins { id("com.android.application") }
android {
    namespace = "dev.local.supercardprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.supercardprobe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
