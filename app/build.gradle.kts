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

// 업로드 키스토어(Play App Signing) — 저장소 밖(projects/keystores)에 두고 keystore.properties 로만 읽는다.
// 파일이 없으면 release 빌드는 서명 없이 만들어지고(스토어 업로드 불가), 저장소에는 아무 키도 들어가지 않는다.
val keystorePropertiesFile: File? = listOf(
    rootProject.file("../keystores/keystore.properties"),
    rootProject.file("keystore.properties")
).firstOrNull { it.exists() }
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile != null) keystorePropertiesFile.inputStream().use { load(it) }
}

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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.youngsu.pinlink"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.8"

        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "CARTO_KEY", "\"$cartoKey\"")
        buildConfigField("String", "NAVER_MAP_CLIENT_ID", "\"$naverMapClientId\"")

        // AdMob 앱 ID — 기본(debug)은 Google 공식 테스트 ID, release 는 buildTypes 에서 실제 ID 로 바꾼다.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"

        // 광고 단위 ID — debug 는 Google 공식 테스트 ID. release 는 buildTypes 에서 실제 ID 로 바꾼다.
        // 코드 상수가 아니라 BuildConfig 로 넣어야 release dex 에 테스트 ID 문자열이 남지 않는다.
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", "\"ca-app-pub-3940256099942544/1033173712\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile != null) {
            create("release") {
                val storeFileName = keystoreProperties.getProperty("storeFile")
                val candidates = listOf(
                    rootProject.file(storeFileName),
                    keystorePropertiesFile.parentFile.resolve(storeFileName)
                )
                storeFile = candidates.firstOrNull { it.exists() }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
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
            // Play 스토어 업로드용 서명 — keystore.properties 가 있을 때만 붙는다.
            signingConfigs.findByName("release")?.let { signingConfig = it }

            // release 빌드는 실제 AdMob ID (PinLink) 사용
            manifestPlaceholders["admobAppId"] = "ca-app-pub-4832055633016363~3545667688"
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-4832055633016363/2875196806\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", "\"ca-app-pub-4832055633016363/1562115134\"")
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
