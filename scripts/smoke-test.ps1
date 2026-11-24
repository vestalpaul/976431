param(
    [string]$ApkPath = "C:\Users\vesta\.gemini\antigravity-ide\scratch\SchwabQuantAndroid\app\build\outputs\apk\debug\SchwabQuant-V1.9-debug.apk",
    [string]$PackageName = "com.schwabquant.app",
    [string]$ExpectedVersionName = "1.9",
    [int]$ExpectedVersionCode = 23,
    [int]$SettleSeconds = 5
)

$ErrorActionPreference = "Continue"

function Get-InstalledVersion {
    param([string]$AdbExe, [string]$Pkg)
    $dump = & $AdbExe shell dumpsys package $Pkg 2>$null
    $vCode = ($dump | Select-String -Pattern "versionCode=(\d+)" | Select-Object -First 1)
    $vName = ($dump | Select-String -Pattern "versionName=([^\s]+)" | Select-Object -First 1)
    if ($vCode -and $vName) {
        return [PSCustomObject]@{
            Code = [int]$vCode.Matches[0].Groups[1].Value
            Name = $vName.Matches[0].Groups[1].Value
        }
    }
    return $null
}

# 1. Locate ADB
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source } else {
        Write-Error "adb.exe not found at '$adb' or on PATH."
        exit 1
    }
}

# 2. Verify APK exists
if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found: $ApkPath"
    exit 1
}
$apkInfo = Get-Item $ApkPath
Write-Host "[1/6] APK verified: $($apkInfo.Name) ($([Math]::Round($apkInfo.Length / 1MB, 2)) MB, modified $($apkInfo.LastWriteTime))" -ForegroundColor Cyan

# 3. Verify connected, authorized device
$deviceLines = (& $adb devices) | Where-Object { $_ -match "\tdevice$" }
if (-not $deviceLines) {
    Write-Host "Current adb devices output:" -ForegroundColor Yellow
    & $adb devices
    Write-Error "No authorized Android device detected. Unlock phone and accept USB debugging prompt."
    exit 1
}
$deviceSerial = ($deviceLines | Select-Object -First 1).Split("`t")[0]
Write-Host "[2/6] Device connected: $deviceSerial" -ForegroundColor Cyan

# 4. Capture pre-update version
$before = Get-InstalledVersion -AdbExe $adb -Pkg $PackageName
if ($before) {
    Write-Host "[3/6] Existing install: V$($before.Name) (versionCode=$($before.Code))" -ForegroundColor Gray
}
else {
    Write-Host "[3/6] No existing install detected (fresh install)." -ForegroundColor Gray
}

# 5. Install as in-place update (-r preserves Room DB & Tink vault)
Write-Host "[4/6] Installing update (adb install -r)..." -ForegroundColor Cyan
$installOut = & $adb -s $deviceSerial install -r "$ApkPath" 2>&1
$installExit = $LASTEXITCODE
$installOut | ForEach-Object { Write-Host "      $_" }
if ($installExit -ne 0 -or -not ($installOut -match "Success")) {
    Write-Error "APK update failed (exit=$installExit). If signatures differ, uninstall the prior build first."
    exit 1
}

$after = Get-InstalledVersion -AdbExe $adb -Pkg $PackageName
if (-not $after -or $after.Name -ne $ExpectedVersionName -or $after.Code -ne $ExpectedVersionCode) {
    Write-Error "Version mismatch after install! Expected V$ExpectedVersionName ($ExpectedVersionCode), got V$($after.Name) ($($after.Code))."
    exit 1
}
Write-Host "      Verified installed version: V$($after.Name) (versionCode=$($after.Code))" -ForegroundColor Green

# 6. Cold-start smoke test
Write-Host "[5/6] Running cold-start smoke test (${SettleSeconds}s settle)..." -ForegroundColor Cyan
& $adb -s $deviceSerial shell am force-stop $PackageName | Out-Null
& $adb -s $deviceSerial logcat -c | Out-Null
$launchOut = & $adb -s $deviceSerial shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 2>&1
if ($LASTEXITCODE -ne 0) {
    $launchOut | ForEach-Object { Write-Host "      $_" -ForegroundColor Yellow }
    Write-Error "Failed to launch $PackageName."
    exit 1
}

Start-Sleep -Seconds $SettleSeconds

# 7. Process liveness + crash/ANR log inspection
Write-Host "[6/6] Checking process liveness and crash logs..." -ForegroundColor Cyan
$pidOut = (& $adb -s $deviceSerial shell pidof $PackageName 2>$null).Trim()
$crashLog = (& $adb -s $deviceSerial logcat -d -b crash 2>$null) -join "`n"
$appErrorLines = (& $adb -s $deviceSerial logcat -d *:E 2>$null) |
Select-String -Pattern "FATAL EXCEPTION|ANR in $PackageName|SQLiteException|IllegalStateException.*Room|UnsatisfiedLinkError"

$failed = $false
if ([string]::IsNullOrWhiteSpace($pidOut)) {
    Write-Host "FAIL: Process $PackageName is not running after ${SettleSeconds}s (likely crashed on startup)." -ForegroundColor Red
    $failed = $true
}
else {
    Write-Host "      Process alive (PID: $pidOut)" -ForegroundColor Green
}

if ($crashLog -match [regex]::Escape($PackageName) -or $crashLog -match "FATAL EXCEPTION") {
    Write-Host "FAIL: Crash buffer contains fatal entries:" -ForegroundColor Red
    Write-Host $crashLog -ForegroundColor Red
    $failed = $true
}
else {
    Write-Host "      Crash buffer clean (0 fatal crashes)" -ForegroundColor Green
}

if ($appErrorLines) {
    Write-Host "WARN/FAIL: High-severity error lines detected in logcat:" -ForegroundColor Yellow
    $appErrorLines | ForEach-Object { Write-Host "      $_" -ForegroundColor Yellow }
    $failed = $true
}

if ($failed) {
    Write-Host "`nSMOKE TEST FAILED for V$ExpectedVersionName (Build $ExpectedVersionCode)." -ForegroundColor Red
    exit 1
}

Write-Host "`nSMOKE TEST PASSED: SchwabQuant V$ExpectedVersionName (Build $ExpectedVersionCode) updated and launched cleanly." -ForegroundColor Green
exit 0