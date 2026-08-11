# Pack Magisk module zip for VCamGD (Zygisk + Pine, sem LSPosed)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$module = Join-Path $root "module"
$outDir = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 1) hook.dex + libpine
& powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build-hook-dex.ps1")
if ($LASTEXITCODE -ne 0) { throw "build-hook-dex failed" }

# 2) zygisk .so
$ndk = "C:\Users\GD\AppData\Local\Android\Sdk\ndk\26.1.10909125"
Push-Location $module
& "$ndk\ndk-build.cmd" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./jni/Android.mk NDK_APPLICATION_MK=./jni/Application.mk -j4
if ($LASTEXITCODE -ne 0) { throw "ndk-build failed" }
Copy-Item ".\libs\arm64-v8a\libvcamgd.so" ".\zygisk\arm64-v8a.so" -Force
Pop-Location

$zip = Join-Path $outDir "vcamgd-magisk-zygisk.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$createMode = [System.IO.Compression.ZipArchiveMode]::Create
$zipFile = [System.IO.Compression.ZipFile]::Open($zip, $createMode)
function Add-ZipEntry($archive, $sourcePath, $entryName) {
  if (-not (Test-Path $sourcePath)) { throw "missing $sourcePath" }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $archive, $sourcePath, $entryName.Replace("\", "/"),
    [System.IO.Compression.CompressionLevel]::Optimal
  ) | Out-Null
}

Add-ZipEntry $zipFile (Join-Path $module "module.prop") "module.prop"
Add-ZipEntry $zipFile (Join-Path $module "customize.sh") "customize.sh"
Add-ZipEntry $zipFile (Join-Path $module "service.sh") "service.sh"
Add-ZipEntry $zipFile (Join-Path $module "uninstall.sh") "uninstall.sh"
Add-ZipEntry $zipFile (Join-Path $module "zygisk\arm64-v8a.so") "zygisk/arm64-v8a.so"
Add-ZipEntry $zipFile (Join-Path $module "dex\hook.dex") "dex/hook.dex"
Add-ZipEntry $zipFile (Join-Path $module "lib\arm64-v8a\libpine.so") "lib/arm64-v8a/libpine.so"
Add-ZipEntry $zipFile (Join-Path $module "META-INF\com\google\android\update-binary") "META-INF/com/google/android/update-binary"
Add-ZipEntry $zipFile (Join-Path $module "META-INF\com\google\android\updater-script") "META-INF/com/google/android/updater-script"
$zipFile.Dispose()

Write-Host "Packed: $zip"
(Get-Item $zip).Length
