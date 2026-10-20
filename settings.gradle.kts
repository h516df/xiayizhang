pluginManagement {
 // Rich Editor 1.0.0 publishes Kotlin 2.4 metadata. Use the supported dexer without
 // upgrading the application's Kotlin, AGP, SDK or Compose compiler.
 buildscript {
  repositories { google(); mavenCentral() }
  dependencies { classpath("com.android.tools:r8:9.1.56") }
 }
 repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "PrivateReader"
include(":app")
