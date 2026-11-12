[CmdletBinding()]
param(
    [string]$WatchIP = "<WATCH_IP>",
    [int]$AdbPort = 5555
)

$ErrorActionPreference = "Stop"

# Auto-detect Android SDK
$sdk = $env:ANDROID_HOME
if (-not $sdk -or -not (Test-Path $sdk)) {
    $sdk = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not (Test-Path $sdk)) {
    throw "Android SDK not found. Set ANDROID_HOME or install Android Studio SDK."
}

# Auto-detect latest build-tools and platform
$buildToolsDir = Get-ChildItem -Path "$sdk\build-tools" | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$platformJar = Get-ChildItem -Path "$sdk\platforms" | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "android.jar" } | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $buildToolsDir -or -not $platformJar) {
    throw "Android build tools or android.jar platform not found in $sdk."
}

$aapt2 = "$buildToolsDir\aapt2.exe"
$d8 = "$buildToolsDir\d8.bat"
$zipalign = "$buildToolsDir\zipalign.exe"
$apksigner = "$buildToolsDir\apksigner.bat"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir

$appDir = "$repoRoot\app\src\main"
$srcDir = "$appDir\java"
$resDir = "$appDir\res"
$manifest = "$appDir\AndroidManifest.xml"
$buildDir = "$repoRoot\build"
$keystore = "$repoRoot\debug.keystore"

Write-Host "Using Android SDK: $sdk"
Write-Host "Using Build-Tools: $buildToolsDir"
Write-Host "Using Platform:    $platformJar"

# Prepare clean build dirs
Remove-Item -Path "$buildDir\*" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path "$buildDir\compiled" -Force | Out-Null
New-Item -ItemType Directory -Path "$buildDir\gen" -Force | Out-Null
New-Item -ItemType Directory -Path "$buildDir\classes" -Force | Out-Null

Write-Host "1. Compiling resources with aapt2..."
& $aapt2 compile --dir $resDir -o "$buildDir\compiled\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "2. Linking resources and generating R.java..."
& $aapt2 link -I $platformJar --manifest $manifest --java "$buildDir\gen" -o "$buildDir\app-unsigned.apk" "$buildDir\compiled\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "3. Compiling Java sources with javac..."
$javaFiles = Get-ChildItem -Path $srcDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
$genFiles = Get-ChildItem -Path "$buildDir\gen" -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
javac -source 8 -target 8 -cp $platformJar -d "$buildDir\classes" ($javaFiles + $genFiles)
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "4. Dexing bytecode with d8..."
$classFiles = Get-ChildItem -Path "$buildDir\classes" -Recurse -Filter "*.class" | ForEach-Object { "`"$($_.FullName)`"" }
$classFilesArg = $classFiles -join " "
cmd.exe /c "`"$d8`" --output `"$buildDir`" $classFilesArg"
if ($LASTEXITCODE -ne 0) { throw "d8 dexing failed" }

Write-Host "5. Packaging classes.dex..."
Push-Location $buildDir
try {
    jar.exe uf "$buildDir\app-unsigned.apk" classes.dex
} finally {
    Pop-Location
}

Write-Host "6. Aligning APK with zipalign..."
& $zipalign -f -v 4 "$buildDir\app-unsigned.apk" "$buildDir\app-aligned.apk" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "7. Signing APK..."
if (-not (Test-Path $keystore)) {
    Write-Host "Creating local debug keystore..."
    keytool -genkeypair -v -keystore $keystore -alias androiddebugkey -keypass android -storepass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}
$finalApk = "$buildDir\DeskAlert.apk"
cmd.exe /c "$apksigner sign --ks `"$keystore`" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out `"$finalApk`" `"$buildDir\app-aligned.apk`""
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "APK built successfully: $finalApk"

if ($WatchIP -and $WatchIP -ne "<WATCH_IP>") {
    Write-Host "8. Connecting to watch at ${WatchIP}:${AdbPort}..."
    adb connect "${WatchIP}:${AdbPort}"
    adb -s "${WatchIP}:${AdbPort}" install -r "$finalApk"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Reinstall fallback..."
        adb -s "${WatchIP}:${AdbPort}" uninstall com.fossil.deskalert
        adb -s "${WatchIP}:${AdbPort}" install "$finalApk"
    }

    Write-Host "9. Launching application..."
    adb -s "${WatchIP}:${AdbPort}" shell am force-stop com.fossil.deskalert
    adb -s "${WatchIP}:${AdbPort}" shell am start -n com.fossil.deskalert/.MainActivity
    Write-Host "Done! App is running on the watch."
} else {
    Write-Host "To push directly to your watch over Wi-Fi, run:"
    Write-Host "  .\scripts\build_and_install.ps1 -WatchIP <YOUR_WATCH_IP>"
}
