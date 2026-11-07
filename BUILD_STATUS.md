# Build verification status

- `AxleRules.kt`: compiled successfully with the locally available Kotlin compiler.
- Calculation unit tests: 20/20 passed.
- Android XML resources: parsed successfully.
- Full Android Gradle build: not run in this environment because Android SDK and Gradle distribution are not installed and outbound DNS/network is unavailable.
- GitHub Actions workflow is configured to install JDK 17, Android SDK 36 and Gradle 8.13, run tests, build `app-debug.apk`, and upload it as an artifact.

Do not treat the project archive itself as an APK.
