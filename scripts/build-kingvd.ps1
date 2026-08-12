# Build kingvd (motor proprio) e embute em assets
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ndk = "C:\Users\GD\AppData\Local\Android\Sdk\ndk\26.1.10909125"
$proj = Join-Path $root "native\kingvd"
$assets = Join-Path $root "app\src\main\assets\king-engine"

Push-Location $proj
& "$ndk\ndk-build.cmd" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./jni/Android.mk NDK_APPLICATION_MK=./jni/Application.mk -j4
if ($LASTEXITCODE -ne 0) { throw "ndk-build kingvd failed" }
Pop-Location

foreach ($abi in @("arm64-v8a", "armeabi-v7a")) {
  $src = Join-Path $proj "libs\$abi\kingvd"
  if (-not (Test-Path $src)) { throw "missing $src" }
  $dstDir = Join-Path $assets $abi
  New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
  Copy-Item $src (Join-Path $dstDir "kingvd") -Force
  Write-Host "embedded $abi/kingvd $((Get-Item $src).Length)"
}
Write-Host "OK kingvd -> app/src/main/assets/king-engine"
