plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mhacademy.videodownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mhacademy.videodownloader"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // বেশিরভাগ আধুনিক ফোন arm64। সাইজ কমাতে শুধু এটি রাখা হয়েছে।
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // youtubedl-android-এর জন্য বাধ্যতামূলক (native lib এক্সট্র্যাক্ট করতে হয়)
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
