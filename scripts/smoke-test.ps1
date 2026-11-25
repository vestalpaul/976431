param(
    [string]$ApkPath = "",
    [string]$PackageName = "com.schwabquant.app",
    [string]$ExpectedVersionName = "1.9.1",
    [int]$ExpectedVersionCode = 24,
    [string]$DeviceSerial = "",
    [int]$SettleSeconds = 5
)

$ErrorActionPreference = "Continue"

function Get-InstalledVersion {
    param([string]$AdbExe, [string]$Serial, [string]$Pkg)
    $dump = & $AdbExe -s $Serial shell dumpsys package $Pkg 2>$null
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

# 2. Resolve APK path (defaults to .\releases\SchwabQuant-V<ExpectedVersionName>-debug.apk)
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $candidate = Join-Path $PSScriptRoot "..\releases\SchwabQuant-V$ExpectedVersionName-debug.apk"
    if (Test-Path $candidate) {
        $ApkPath = (Resolve-Path $candidate).Path
    }
    else {
        $latest = Get-ChildItem (Join-Path $PSScriptRoot "..\releases\SchwabQuant-V*-debug.apk") -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latest) { $ApkPath = $latest.FullName }
    }
}

if ([string]::IsNullOrWhiteSpace($ApkPath) -or -not (Test-Path $ApkPath)) {
    Write-Error "APK not found. Pass -ApkPath explicitly or verify .\releases\ contains SchwabQuant-V$ExpectedVersionName-debug.apk."
    exit 1
}
$apkInfo = Get-Item $ApkPath
Write-Host "[1/6] APK verified: $($apkInfo.Name) ($([Math]::Round($apkInfo.Length / 1MB, 2)) MB, modified $($apkInfo.LastWriteTime))" -ForegroundColor Cyan

# 3. Verify connected, authorized device (with wireless offline auto-recovery)
$rawDevices = & $adb devices
if (($rawDevices | Where-Object { $_ -match "\toffline$" }) -and -not ($rawDevices | Where-Object { $_ -match "\tdevice$" })) {
    Write-Host "      Wireless ADB target is offline; attempting 'adb reconnect offline'..." -ForegroundColor Yellow
    & $adb reconnect offline | Out-Null
    Start-Sleep -Seconds 2
    $rawDevices = & $adb devices
}

$activeSerials = @($rawDevices | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { $_.Split("`t")[0] })
if ($activeSerials.Count -eq 0) {
    Write-Host "Current adb devices output:" -ForegroundColor Yellow
    $rawDevices | ForEach-Object { Write-Host "      $_" }
    Write-Error "No authorized device found. Toggle Wireless Debugging on your phone or run 'adb connect <ip>:<port>'."
    exit 1
}

if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($activeSerials -notcontains $DeviceSerial) {
        Write-Error "Requested -DeviceSerial '$DeviceSerial' is not connected. Active targets: $($activeSerials -join ', ')"
        exit 1
    }
    $targetSerial = $DeviceSerial
}
else {
    # Prefer wireless target if both USB and Wi-Fi are active
    $wireless = $activeSerials | Where-Object { $_ -match "_adb-tls-connect\._tcp|:\d+$" } | Select-Object -First 1
    $targetSerial = if ($wireless) { $wireless } else { $activeSerials[0] }
}
$transport = if ($targetSerial -match "_adb-tls-connect\._tcp|:\d+$") { "Wireless ADB" } else { "USB ADB" }
Write-Host "[2/6] Device connected ($transport): $targetSerial" -ForegroundColor Cyan

# 4. Capture pre-update version
$before = Get-InstalledVersion -AdbExe $adb -Serial $targetSerial -Pkg $PackageName
if ($before) {
    Write-Host "[3/6] Existing install: V$($before.Name) (versionCode=$($before.Code))" -ForegroundColor Gray
}
else {
    Write-Host "[3/6] No existing install detected (fresh install)." -ForegroundColor Gray
}

# 5. Install as in-place update (-r preserves Room DB & Tink vault)
Write-Host "[4/6] Installing update over $transport (adb install -r)..." -ForegroundColor Cyan
$installOut = & $adb -s $targetSerial install -r "$ApkPath" 2>&1
$installExit = $LASTEXITCODE
$installOut | ForEach-Object { Write-Host "      $_" }
if ($installExit -ne 0 -or -not ($installOut -match "Success")) {
    Write-Error "APK update failed (exit=$installExit). Check Wi-Fi stability or uninstall if signing keys differ."
    exit 1
}

$after = Get-InstalledVersion -AdbExe $adb -Serial $targetSerial -Pkg $PackageName
if (-not $after -or $after.Name -ne $ExpectedVersionName -or $after.Code -ne $ExpectedVersionCode) {
    Write-Error "Version mismatch after install! Expected V$ExpectedVersionName ($ExpectedVersionCode), got V$($after.Name) ($($after.Code))."
    exit 1
}
Write-Host "      Verified installed version: V$($after.Name) (versionCode=$($after.Code))" -ForegroundColor Green

# 6. Cold-start smoke test
Write-Host "[5/6] Running cold-start smoke test (${SettleSeconds}s settle)..." -ForegroundColor Cyan
& $adb -s $targetSerial shell am force-stop $PackageName | Out-Null
& $adb -s $targetSerial logcat -c | Out-Null
$launchOut = & $adb -s $targetSerial shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 2>&1
if ($LASTEXITCODE -ne 0) {
    $launchOut | ForEach-Object { Write-Host "      $_" -ForegroundColor Yellow }
    Write-Error "Failed to launch $PackageName."
    exit 1
}

Start-Sleep -Seconds $SettleSeconds

# 7. Process liveness + crash/ANR log inspection
Write-Host "[6/6] Checking process liveness and crash logs..." -ForegroundColor Cyan
$pidOut = (& $adb -s $targetSerial shell pidof $PackageName 2>$null).Trim()
$crashLog = (& $adb -s $targetSerial logcat -d -b crash 2>$null) -join "`n"
$appErrorLines = (& $adb -s $targetSerial logcat -d *:E 2>$null) |
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