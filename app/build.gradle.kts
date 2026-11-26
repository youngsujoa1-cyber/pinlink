import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 서버·지도 키는 소스에 적지 않고 local.properties(저장소 제외)에서 읽어 BuildConfig 로 넣는다.
// 값이 비어 있으면 앱은 '데모 모드'(친구 2명 시뮬레이션)로 동작한다 — 설치만 해도 화면이 살아 있다.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun prop(name: String, fallback: String = ""): String =
    localProperties.getProperty(name) ?: System.getenv(name) ?: fallback

// Firebase 프로젝트 — PinLink 전용 무료 Spark 프로젝트(pinlink-live). 키가 있어야 실제 통신한다
val firebaseProjectId: String = prop("FIREBASE_PROJECT_ID", "pinlink-live")
// Firebase 웹 API 키 — 비어 있으면 데모 모드
val firebaseApiKey: String = prop("FIREBASE_API_KEY")
// Carto 타일 키(선택) — 없으면 OpenStreetMap 표준 타일로 그린다
val cartoKey: String = prop("CARTO_KEY")
// 네이버 지도(NCP Dynamic Map) 클라이언트 ID — 비어 있으면 전 세계를 CARTO 지도로만 그린다.
// 국내 지도만 네이버 지도(WebView)로 그리고, 해외는 CARTO 로 자동 전환한다.
val naverMapClientId: String = prop("NAVER_MAP_CLIENT_ID")

android {
    namespace = "com.youngsu.pinlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.youngsu.pinlink"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.6"

        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "CARTO_KEY", "\"$cartoKey\"")
        buildConfigField("String", "NAVER_MAP_CLIENT_ID", "\"$naverMapClientId\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // 배포 전 테스트용 설치 파일 — 패키지명은 그대로 (나중에 정식 버전과 같은 앱으로 인식)
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // TODO(PlayStore): 업로드 키(keystore)를 만들면 여기에 signingConfig 를 연결한다.
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)

    implementation(libs.play.services.ads)
    // play-services-ads 가 요구하는 ListenableFuture (오프라인 캐시에 있는 버전으로 고정)
    implementation(libs.guava)
}
