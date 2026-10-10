# Third-party notices — pre-build inventory

No APK has been produced. This is a source checkpoint, not a verified inventory of packaged binary dependencies. Final APK license inspection remains a release gate.

| Component configured or used | Role | License / status |
|---|---|---|
| Kotlin / Kotlin Compose compiler | Android language/compiler | Apache-2.0 |
| AndroidX Activity, Compose, Lifecycle, DocumentFile, WebKit | Android UI/runtime libraries | Apache-2.0; artifacts not downloaded here |
| kotlinx.coroutines | Background work | Apache-2.0; artifact not downloaded here |
| Android Gradle Plugin | Build tool | Apache-2.0; artifact not downloaded here |
| Gradle | Build tool | Apache-2.0; official 8.13 Wrapper bundled, checksum verified; full distribution not downloaded |
| Android SDK, emulator, system WebView | Platform/tooling | Distributed separately under their own terms; not included |
| @electron/asar 4.2.0 | Planned official fixture cross-check | MIT; not downloaded, not bundled, not executed |
| OpenJDK 17 runtime/compiler | Executed JVM core tests | GPLv2 with Classpath Exception; not bundled |
| Node.js | Executed JS tests | Node.js MIT-based distribution; not bundled |
| Python | Fixture generation / build helpers | PSF license; not bundled |
| cryptography | Maintainer-only signing key generation | Apache-2.0 / BSD-3-Clause; not bundled |

Electron ASAR documentation and Chromium Pickle behavior informed the independently written parser. No Electron, Chromium, or TyranoScript engine source code is bundled in this source checkpoint. The synthetic VN fixture, generated test pattern image, and generated sine-wave audio were written for this project.

asar4j was researched but is not a dependency. No proprietary game assets, EXE/DLL runtime, DRM bypass, or real user game is included.

Before a release, resolve all dependencies, preserve their LICENSE/NOTICE files, inspect transitive dependencies and the final APK, and include an accurate final notices file. This unfinished inventory does not substitute for that audit.

Bundled Gradle Wrapper and launcher scripts: official Gradle commit `073314332697ba45c16c0a0ce1891fa6794179ff`. License text: `licenses/Gradle-Apache-2.0.txt`; upstream copyright/license headers retained.
