param(
    [ValidateSet("minor", "patch", "major", "none")]
    [string]$Bump = "minor",

    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",

    [switch]$Install
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($Bump -ne "none") {
        Write-Host "==> Bumping version ($Bump)..."
        & .\gradlew.bat bumpVersion "-Pbump=$Bump" --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "bumpVersion failed with exit code $LASTEXITCODE"
        }
    }

    $assembleTask = ":app:assemble" + $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1).ToLower()
    Write-Host "==> Building ($assembleTask) + Running Unit Tests..."
    & .\gradlew.bat printVersion :app:compileDebugKotlin :app:testDebugUnitTest $assembleTask --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle iteration build failed with exit code $LASTEXITCODE"
    }

    $apkDir = Join-Path $projectRoot "app\build\outputs\apk\$Variant"
    $apk = Get-ChildItem -Path $apkDir -Filter "SchwabQuant-V*-$Variant*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $apk) {
        throw "No stamped APK matching SchwabQuant-V*-$Variant*.apk found under $apkDir"
    }

    $sizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Host "==> Stamped APK Ready: $($apk.FullName) ($sizeMb MB)"

    if ($Install) {
        $adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (-not (Test-Path $adbPath)) {
            throw "adb.exe not found at $adbPath"
        }
        Write-Host "==> Installing via adb..."
        & $adbPath install -r $apk.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "adb install failed with exit code $LASTEXITCODE"
        }
    }
} finally {
    Pop-Location
}
