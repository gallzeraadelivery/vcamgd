# Pack Magisk module zip for VCamGD
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$module = Join-Path $root "module"
$outDir = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$ndk = "C:\Users\GD\AppData\Local\Android\Sdk\ndk\26.1.10909125"
Push-Location $module
& "$ndk\ndk-build.cmd" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./jni/Android.mk NDK_APPLICATION_MK=./jni/Application.mk -j4
if ($LASTEXITCODE -ne 0) { throw "ndk-build failed" }
Copy-Item ".\libs\arm64-v8a\libvcamgd.so" ".\zygisk\arm64-v8a.so" -Force
Pop-Location

$zip = Join-Path $outDir "vcamgd-magisk-zygisk.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }

$stage = Join-Path $env:TEMP "vcamgd-module-pack"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$files = @(
  "module.prop",
  "customize.sh",
  "service.sh",
  "uninstall.sh",
  "zygisk\arm64-v8a.so",
  "META-INF\com\google\android\update-binary",
  "META-INF\com\google\android\updater-script"
)
foreach ($f in $files) {
  $src = Join-Path $module $f
  $dst = Join-Path $stage $f
  New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
  Copy-Item $src $dst -Force
}

Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip -Force
# Compress-Archive nests oddly on some PS versions; rebuild with .NET zip for flat structure
Remove-Item $zip -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipFile = [System.IO.Compression.ZipFile]::Open($zip, [System.IO.Compression.ZipArchiveMode]::Create)
function Add-ZipEntry($archive, $sourcePath, $entryName) {
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $archive, $sourcePath, $entryName.Replace("\", "/"),
    [System.IO.Compression.CompressionLevel]::Optimal
  ) | Out-Null
}
Add-ZipEntry $zipFile (Join-Path $stage "module.prop") "module.prop"
Add-ZipEntry $zipFile (Join-Path $stage "customize.sh") "customize.sh"
Add-ZipEntry $zipFile (Join-Path $stage "service.sh") "service.sh"
Add-ZipEntry $zipFile (Join-Path $stage "uninstall.sh") "uninstall.sh"
Add-ZipEntry $zipFile (Join-Path $stage "zygisk\arm64-v8a.so") "zygisk/arm64-v8a.so"
Add-ZipEntry $zipFile (Join-Path $stage "META-INF\com\google\android\update-binary") "META-INF/com/google/android/update-binary"
Add-ZipEntry $zipFile (Join-Path $stage "META-INF\com\google\android\updater-script") "META-INF/com/google/android/updater-script"
$zipFile.Dispose()

Write-Host "Packed: $zip"
(Get-Item $zip).Length
