# Start a release. GitHub builds the APK and publishes it (see .github/workflows/release.yml).
#   1. Write the description in docs\releases\v<Version>.md
#   2. .\scripts\release.ps1 -Version 1.0.1
param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$tag = "v$Version"

$notesFile = "docs\releases\$tag.md"
if (-not (Test-Path $notesFile) -or -not (Get-Content $notesFile -Raw).Trim()) {
    throw "Write the release description first: $notesFile"
}

$gradle = Get-Content app\build.gradle.kts -Raw
$code = [int]([regex]::Match($gradle, 'versionCode = (\d+)').Groups[1].Value)
$oldName = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
if ($oldName -ne $Version) {
    $gradle = $gradle -replace 'versionCode = \d+', "versionCode = $($code + 1)"
    $gradle = $gradle -replace 'versionName = "[^"]+"', "versionName = `"$Version`""
    Set-Content app\build.gradle.kts $gradle -NoNewline -Encoding UTF8
}

git add -A
git commit -m "Release $tag" 2>&1 | Out-Null
git tag -a $tag -m "Obsi Bible $tag"
git push origin HEAD
git push origin $tag
Write-Host "Pushed $tag. GitHub is building it now: see the Actions tab, then Releases."
