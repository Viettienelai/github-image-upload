plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

android {
    namespace = "com.obsidiansync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.obsidiansync"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true // Bật R8 để giảm dung lượng tối đa
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        // Change this from whatever it is (likely 21) to "11"
        jvmTarget = "11"
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Dùng Material 2 thay vì Material 3 để nhẹ hơn
    implementation("androidx.compose.material:material:1.6.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Google Drive & Auth (Tối ưu bản nhẹ)
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    // Room
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.material:material:1.6.1")
    implementation("androidx.compose.ui:ui")
    // THÊM DÒNG NÀY: Để dùng các icon như Storage, CloudUpload, v.v.
    implementation("androidx.compose.material:material-icons-extended:1.6.1")

    // Google Drive & Auth
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0")
    // THÊM DÒNG NÀY: Để sửa lỗi Unresolved reference 'extensions' và 'AndroidHttp'
    implementation("com.google.http-client:google-http-client-android:1.43.3")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")
}