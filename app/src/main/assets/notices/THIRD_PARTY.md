# Third-party components

This project uses dependencies from their official Maven repositories. It does not copy reader application source, copyrighted novel text, cover artwork, or copyleft UI assets.

- AndroidX / Jetpack Compose / Activity / Core: Apache License 2.0.
- Kotlin and kotlinx.coroutines: Apache License 2.0.
- Gradle Wrapper and Android Gradle Plugin: Apache License 2.0. Wrapper distribution URL and SHA-256 are pinned in gradle/wrapper/gradle-wrapper.properties.
- JUnit 4 (tests only): Eclipse Public License 1.0.
- Robolectric 4.17 (tests only): MIT License, with Apache-2.0 licensed subsets.
- Roborazzi 1.76.0 (tests only): Apache License 2.0.
- JSON-java (tests only): public domain, per upstream project.

Consult each resolved component's own license and notices before a store or public release. Dependencies are not vendored here except the standard Gradle wrapper bootstrap JAR. All demonstration fiction and the simple vector book icon were created for this prototype.

## Reading/editing integration

- Readium Kotlin Toolkit, 3.3.0, Readium Foundation, BSD 3-Clause.
  https://github.com/readium/kotlin-toolkit/tree/3.3.0
  Used as the packaged stable EPUB Navigator module; not the alpha Compose navigator.
  License: licenses/Readium-BSD-3-Clause.txt (also bundled in notices).
- Compose Rich Editor, 1.0.0, Mohamed Rejeb, Apache 2.0.
  https://github.com/MohamedRejeb/Compose-Rich-Editor/tree/v1.0.0
  Used for bounded selection editing and its built-in undo/redo history. The app does not claim to
  preserve rich formatting in its plain-text working-copy model.
  License: licenses/Compose-Rich-Editor-Apache-2.0.txt (also bundled in notices).

Readium also packages font assets. Their original packaged license files remain intact.
The older bundled OpenDyslexic font attribution and Bitstream license are reproduced
in licenses/Readium-bundled-font-notices.txt and the app notices assets.
