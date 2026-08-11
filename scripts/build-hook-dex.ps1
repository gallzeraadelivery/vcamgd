# Build hook.dex (VideoFeeder + Pine) for Magisk Zygisk injection
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$sdk = "C:\Users\GD\AppData\Local\Android\Sdk"
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$androidJar = "$sdk\platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) { $androidJar = "$sdk\platforms\android-36\android.jar" }
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$($bt.FullName);$env:Path"

$out = Join-Path $root "hook-src\build"
$classes = Join-Path $out "classes"
$dexDir = Join-Path $root "module\dex"
New-Item -ItemType Directory -Force -Path $classes, $dexDir, $out | Out-Null

$pineJar = Join-Path $root "module\third_party\pine-aar\classes.jar"
$src = Join-Path $root "hook-src\src\main\java\com\vcamgd\hook\HookEntry.java"

Write-Host "Compiling HookEntry.java..."
& javac -encoding UTF-8 -classpath "$androidJar;$pineJar" -d $classes $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "Running d8..."
$jarOut = Join-Path $out "hook-classes.jar"
& jar cf $jarOut -C $classes .
& d8.bat --min-api 31 --output $out $jarOut $pineJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$dex = Join-Path $out "classes.dex"
if (-not (Test-Path $dex)) { throw "classes.dex missing" }
Copy-Item $dex (Join-Path $dexDir "hook.dex") -Force

# pine native lib for tmp load
$libOut = Join-Path $root "module\lib\arm64-v8a"
New-Item -ItemType Directory -Force -Path $libOut | Out-Null
Copy-Item (Join-Path $root "module\third_party\pine-aar\jni\arm64-v8a\libpine.so") (Join-Path $libOut "libpine.so") -Force

Write-Host "OK: module\dex\hook.dex + module\lib\arm64-v8a\libpine.so"
(Get-Item (Join-Path $dexDir "hook.dex")).Length
