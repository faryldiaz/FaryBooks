plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.farybooks.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.farybooks.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
        }
        create("demo") {
            dimension = "edition"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
    }
}


dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.reactivestreams:reactive-streams:1.0.4")
}
