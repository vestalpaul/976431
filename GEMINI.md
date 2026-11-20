# Schwab Quant Android — Workspace Engineering Standards

Always consult @SCHWAB_API_REFERENCE.md before writing any Schwab REST, OAuth 2.0, Order JSON, OCC symbol, or WebSocket Streamer code. Never guess Streamer field indices or Schwab enum strings.

## Hard Constraints
* Stack: Kotlin 2.x, Jetpack Compose (Material 3), Hilt, Room, OkHttp/Retrofit, Kotlinx Serialization (`ignoreUnknownKeys = true`, `isLenient = true`).
* Security: Store all Schwab credentials and OAuth tokens exclusively in Android Keystore (`EncryptedSharedPreferences` / Tink). Never hardcode keys or secrets.
* Safety: Enforce Shadow Mode (paper trading) as the default execution mode; never bypass Soft-Gate Risk Warning Dialogs or `BiometricPrompt` confirmations.
* Verification: Run `./gradlew :app:compileDebugKotlin` (and unit tests when applicable) after every milestone and resolve all compiler errors before reporting completion.
* Versioning & Release Rule: Source of truth is `version.properties`. Before assembling the final APK of any new feature, bugfix, or hardening iteration, run `.\gradlew.bat bumpVersion` once (default `minor`; `-Pbump=patch` for hotfixes) in a separate Gradle invocation before `:app:assembleDebug` / `:app:assembleRelease`. Never bump during intermediate compile-fix loops.