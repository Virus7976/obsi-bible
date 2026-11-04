# One-time setup: make the release key and copy it to the clipboard for GitHub secrets.
# The key and password stay in %USERPROFILE%\.obsibible, outside the repo. Back that folder up.
$ErrorActionPreference = 'Stop'
$dir = "$env:USERPROFILE\.obsibible"
$jks = "$dir\release.jks"
if (Test-Path $jks) { throw "A key already exists at $jks. Not overwriting it." }

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) { $keytool = "$env:USERPROFILE\.jdks\temurin-21\bin\keytool.exe" }
if (-not (Test-Path $keytool)) { throw "keytool not found. Set JAVA_HOME to a JDK." }

New-Item -ItemType Directory -Force $dir | Out-Null
$pw = -join ((48..57 + 97..122) | Get-Random -Count 28 | ForEach-Object { [char]$_ })
& $keytool -genkeypair -keystore $jks -alias obsibible -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $pw -keypass $pw -dname "CN=Obsi Bible, O=Personal" | Out-Null
Set-Content "$dir\password.txt" $pw -Encoding ascii

Set-Clipboard ([Convert]::ToBase64String([IO.File]::ReadAllBytes($jks)))
Write-Host ""
Write-Host "STEP 1. The key is on your clipboard."
Write-Host "  GitHub repo > Settings > Secrets and variables > Actions > New repository secret"
Write-Host "  Name:   ANDROID_KEYSTORE_B64"
Write-Host "  Secret: paste (Ctrl+V), then Add secret"
Read-Host "Press Enter when saved"

Set-Clipboard $pw
Write-Host ""
Write-Host "STEP 2. The password is on your clipboard."
Write-Host "  New repository secret again"
Write-Host "  Name:   ANDROID_KEYSTORE_PASSWORD"
Write-Host "  Secret: paste (Ctrl+V), then Add secret"
Read-Host "Press Enter when saved"

Set-Clipboard " "
Write-Host ""
Write-Host "Done. Clipboard cleared. Back up $dir (a copy on a USB stick is enough)."
Write-Host "Your next release is signed with this key."
