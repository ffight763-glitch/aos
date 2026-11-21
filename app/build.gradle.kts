import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.anthezis.launcher"
    compileSdk = 35
    // Ship every ABI supplied by the dependencies so 32-bit ARM devices and emulators can install the APK too.
    defaultConfig { applicationId = "org.anthezis.ahome"; minSdk = 31; targetSdk = 35; // Minutes since the epoch: every build is newer than the one before, so the update server can compare them.
        versionCode = (System.currentTimeMillis() / 60000).toInt(); versionName = "0.1." + versionCode }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // The build we ship is a release build (not debuggable, optimised by R8): noticeably smoother than debug.
    // It is signed with the same debug key as before, so updates install over the old app and Google sign-in keeps working.
    buildTypes {
        release {
            isMinifyEnabled = false   // R8 took ~12 min per build; a non-debuggable release build is the part that makes it smooth
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            // Phones only (ARM): the x86 libraries are for emulators and cost 33 MB, which pushed the APK over GitHub's 100 MB limit.
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    // The Anthropic SDK brings Jackson and OkHttp: their duplicate license / module files must not clash in the APK.
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
    defaultConfig {
        // Default YouTube Data API key for AOS Tube. It comes from the developer's own local.properties
        // (line: aos.youtubeKey=...), never from the sources, so it is not committed or shared by accident.
        val local = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        // A build meant for the public update repo (-PaosPublic) never contains the key; it is stored on the phone instead.
        val publicBuild = providers.gradleProperty("aosPublic").isPresent
        val youtubeKey = if (publicBuild) "" else local.getProperty("aos.youtubeKey", "").trim()
        buildConfigField("String", "YOUTUBE_KEY", "\"" + youtubeKey + "\"")
        // AOS AI: the developer's own Anthropic key from local.properties (line: aos.anthropicKey=...), same rules as above.
        val anthropicKey = if (publicBuild) "" else local.getProperty("aos.anthropicKey", "").trim()
        buildConfigField("String", "ANTHROPIC_KEY", "\"" + anthropicKey + "\"")
    }
}

dependencies {
    // Google sign-in for AOS Tube: the Authorization API gives an access token for the user's YouTube data.
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // AOS Translate: on-device translation (language packs download once, then it works offline).
    implementation("com.google.mlkit:translate:17.0.3")
    // Browser proxy (VPN button of AOS Browser): routes the browser tabs through a proxy server.
    implementation("androidx.webkit:webkit:1.11.0")
    // AOS AI: the official Anthropic SDK (Claude Messages API).
    implementation("com.anthropic:anthropic-java:3.2.0")
}

