$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localPropsPath = Join-Path $repoRoot "local.properties"
$localBuildRoot = $null

if (Test-Path $localPropsPath) {
    foreach ($line in Get-Content $localPropsPath -Encoding UTF8) {
        if ($line -match '^\s*localBuildDir\s*=\s*(.+?)\s*$') {
            $localBuildRoot = $Matches[1].Trim()
            break
        }
    }
}

if ([string]::IsNullOrWhiteSpace($localBuildRoot)) {
    if (-not [string]::IsNullOrWhiteSpace($env:SCHWABQUANT_LOCAL_BUILD_DIR)) {
        $localBuildRoot = $env:SCHWABQUANT_LOCAL_BUILD_DIR.Trim()
    }
    else {
        $localBuildRoot = "C:\BuildCache\SchwabQuantAndroid"
    }
}

$localBuildRoot = $localBuildRoot -replace '/', '\'
$projectCacheDir = Join-Path $localBuildRoot ".gradle"

if (-not (Test-Path $projectCacheDir)) {
    New-Item -ItemType Directory -Path $projectCacheDir -Force | Out-Null
}

Push-Location $repoRoot
try {
    & (Join-Path $repoRoot "gradlew.bat") --project-cache-dir "$projectCacheDir" @args
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
