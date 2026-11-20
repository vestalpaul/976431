---
description: Read-only adversarial audit (v2) covering Shadow Firewall, Schwab v1 JSON, Concurrency/State Machines, Android 14+ FGS/Keystore, and R8 Release/Secret Hygiene.
---

You are an adversarial trading-systems and Android security auditor. Read `@SCHWAB_API_REFERENCE.md` and inspect the codebase strictly read-only to find verified bugs that could cause real financial loss, accidental shorting or margin borrowing, duplicate fills, Schwab API rejections, R8 runtime crashes, or credential leakage.

Audit all five focus areas systematically:

### 1. Shadow-Mode Firewall & Mode-Transition Isolation
* Verify zero reachable paths where `isLiveMode == false` can invoke `SchwabTraderApi.placeOrder` or `SchwabTraderApi.cancelOrder` (excluding strictly scoped in-flight `NonCancellable` cleanup of an order already dispatched while `isLiveMode == true`).
* Verify Shadow-created Sentinel rules (`isShadowMode = true` / `ExecutionMode.SHADOW`), `"SHADOW_PAPER"` hashes, and Shadow working orders can never auto-promote to Live execution or trigger Live cancellations across mode-flip TOCTOU windows.

### 2. Outbound Schwab v1 Order JSON, OCC Symbols & Asset Classification
* Verify strict compliance against `@SCHWAB_API_REFERENCE.md`: `explicitNulls = false` verified on outbound serialization, strictly positive 2-decimal `BigDecimal` prices (validated post-rounding), `LIMIT` + `DAY` enforced outside `NORMAL` (`AM`/`PM`/`SEAMLESS`), `MARKET` restricted to `DAY`, OCO child price sanity (`takeProfit` vs. `stopPrice`), `BRK.B` -> `BRK/B` normalization on equities, `"BUY_TO_COVER"` on short-equity exits, and strict 21-char OCC validation (`ResolverStyle.STRICT`, trailing-space padding only, positive 8-digit strike).
* Verify non-option live position classification handles both `"EQUITY"` and `"COLLECTIVE_INVESTMENT"` (ETFs) across `PortfolioRepositoryImpl`, `SentinelExecutionStateMachine`, and `OrderRepositoryImpl`.

### 3. Concurrency, State Machines, Streamer & Position Guards
* **`MidPriceWalkUpController`:** Verify zero `lifecycleMutex` deadlocks, `operationEpoch` enforcement on `cancelWalkUp` / `startWalkUp`, cancellable `replaceOrder` settlement delays, zero `@Singleton` permanent lockouts on terminal/filled/already-canceled orders, and zero partial-fill over-fills.
* **`SentinelExecutionStateMachine` & `OrderRepositoryImpl`:** Verify per-symbol serialization, fail-closed Step 1/2/3/4 checks (no stale cache fallback), 177-day chunked GTC lookback with clock-skew buffer and complete `ACTIVE_WORKING_STATUSES` (including `AWAITING_STOP_CONDITION`), recursive OCO `childOrderStrategies` leg and fill aggregation, directional whole-share clamping, `allowTerminalOrPartialReprice` anti-short/anti-double-subtraction math, and REST-verified full fills before `TRIGGERED_FILLED`.
* **`SchwabStreamerClient` & `AntiStopHuntEvaluator`:** Verify `connectionGeneration` vs. `ownerEpoch` isolation, atomic `stateLock` transitions, Error 12 reconnect halt, no reconnect self-cancellation, directional `hasFreshBidUpdate` / `hasFreshAskUpdate` gating, and breach-timer reset across halts/blowouts.
* **OAuth Refresh:** Verify `SchwabTokenRefreshCoordinator` has no Main-thread ANR locks, no refresh storm on failure, no post-refresh Keystore decrypt discarding rotated `R_new`, and no concurrent login/logout clobbering.

### 4. Android 14+ Foreground Service, Locks & Hardware Keystore Recovery
* **`SentinelForegroundService`:** Verify `startForeground()` compliance on all `onStartCommand` paths (including synchronous fast-stop on Android 14+ `ForegroundServiceStartNotAllowedException`), liveness across `ARMED -> EXECUTING` and notification handoff, atomic `sessionLock` teardown checks, `WakeLock`/`WifiLock` balance and renewal, and verify `selfHealDisconnectedStreamerIfNeeded()` cannot cause a tight reconnect loop or override `isError12Halted()` / manual stops.
* **`TinkCredentialVault` & `TinkAeadFactory`:** Verify transient KeyMint/Keystore faults never wipe valid credentials, volatile RAM token fallback always takes precedence over stale ciphertext on recovery, `transientFault` clears only after encrypted flush succeeds, and `probeKeystoreRecovery()` + `wipeAllCredentials()` recover cleanly in-process.

### 5. R8 Minification, Release Config & Security Hygiene
* Inspect `app/proguard-rules.pro` and `app/build.gradle.kts` against live classes to confirm R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) will not strip: Kotlinx Serialization `$$serializer` / `Companion` / `SchwabSafeDoubleSerializer` / `ShadowOrderLegPayload` / recursive `OrderResponseDto`, Retrofit API interfaces and generic return types, Tink KMS / proto classes, Room `@Entity`/`@Database`/`_Impl`, `@HiltWorker` (`TokenExpiryCheckWorker`), or `BiometricPrompt` callbacks.
* Check for credential or token leakage in `Log.*`, `AuditLogEntity`, exceptions, or OkHttp interceptors; verify `.gitignore` excludes `keystore.properties`, `*.jks`, and `*.keystore`, and verify `backup_rules.xml` / `data_extraction_rules.xml` exclude Tink/vault SharedPreferences from cloud backup and device transfer.
* Inspect exported `MainActivity` deep-link intent handling (`ACTION_STAGE_SENTINEL_ORDER`) to confirm malformed or external intents cannot bypass Soft-Gate risk checks, biometric confirmation, or fabricate invalid order prices.

### Output Rules
* Do NOT edit any files during this audit pass.
* Return only verified, file-and-line-cited findings ranked by severity (`CRITICAL`, `HIGH`, `MEDIUM`) with exact reproduction logic and financial/runtime impact.
* Conclude with a concise **Verified Clean Controls** summary across Focus Areas 1 through 5.