plugins { id("com.android.application") }
android {
    namespace = "dev.local.supercardhost"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.supercardhost"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-host-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.luckypray:dexkit:2.0.7")
    // Supplied by Vector in scoped processes; do not bundle framework API classes.
    compileOnly("io.github.libxposed:api:102.0.0")
}
