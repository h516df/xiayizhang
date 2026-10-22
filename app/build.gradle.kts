plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "cn.privatereader"; compileSdk = 36
 defaultConfig { applicationId = "cn.privatereader"; minSdk = 26; targetSdk = 36; versionCode = 3; versionName = "0.3" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
 buildFeatures { compose = true }
 buildTypes { getByName("debug") { applicationIdSuffix = ".dev" }; getByName("release") { isDebuggable = false; isMinifyEnabled = false } }
 testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.03.01"))
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.core:core-ktx:1.18.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20250517")
}

// Isolated host rendering experiment; never part of the shipping application.
android.testOptions.unitTests.apply {
    isIncludeAndroidResources = true
    all {
        it.maxHeapSize = "1g"
        it.systemProperty("java.io.tmpdir", rootProject.file(".gradle/test-tmp").also { dir -> dir.mkdirs() }.absolutePath)
        it.systemProperty("user.home", rootProject.file(".gradle/task-home").also { it.mkdirs() }.absolutePath)
        it.systemProperty("roborazzi.test.record", "true")
        it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore", "robolectric.offline", "robolectric.dependency.dir").forEach { key ->
            System.getProperty(key)?.let { value -> it.systemProperty(key, value) }
        }
        it.jvmArgs(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.security=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        )
    }
}
dependencies {
    testImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
}


// Fail closed: unsigned candidates require an explicit opt-in; final signing is external.
gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { it.project == project && it.name in setOf("assembleRelease", "packageRelease", "bundleRelease") }
    if (releaseRequested && providers.gradleProperty("unsignedRelease").orNull != "true") {
        throw GradleException("Official signing requires original injected credentials via cloud/sign_apk.py. Use -PunsignedRelease=true only for an unsigned build candidate.")
    }
}
