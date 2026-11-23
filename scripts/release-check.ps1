<#
.SYNOPSIS
    End-to-end release QA for Sirrak: runs the Gradle gates, verifies the signed artifacts and
    records a machine-readable release report.

.DESCRIPTION
    One command for the local half of the launch gate (docs/27). It reads the paths, task list,
    minimum JVM test count, lint report and signing certificate from store/store-spec.json, so
    changing the release shape is a data edit. Steps:

      1. Gradle: signed release APK/AAB, lint and the full JVM suite (skippable with -SkipBuild).
      2. Test results: sum tests/failures from every build/test-results XML and enforce the minimum.
      3. Lint: zero errors in the lint XML report; warnings are reported, not hidden.
      4. Artifacts: exist, size/SHA-256, apksigner v2 signature + certificate fingerprint for the
         APK, jarsigner verification for the AAB.
      5. Store assets: store/validate-store.ps1 must pass (listing limits, graphics, docs).
      6. Public site: scripts/build-site.ps1 regenerates website/dist from the data files, and
         scripts/validate-site.ps1 must pass (bilingual copy, SEO, policies, brand, drift).
      7. Device (unless -SkipEmulator): ui-regression.ps1 screenshot gate against a booted AVD.
      8. Writes build/release-check.json and prints a pass/fail summary; exits 1 on any failure.

.EXAMPLE
    ./scripts/release-check.ps1
    ./scripts/release-check.ps1 -SkipBuild -SkipEmulator   # re-verify existing artifacts
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SpecFile,
    [string]$Serial = 'emulator-5554',
    [switch]$SkipBuild,
    [switch]$SkipEmulator
)

$ErrorActionPreference = 'Stop'
if (-not $SpecFile) { $SpecFile = Join-Path $RepoRoot 'store/store-spec.json' }
$spec = Get-Content -LiteralPath $SpecFile -Raw | ConvertFrom-Json
$release = $spec.release
if ($null -eq $release) { throw 'store-spec.json has no release section' }

$results = [System.Collections.Generic.List[object]]::new()
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Java\jdk-21')) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:GRADLE_USER_HOME -and (Test-Path 'D:\.gradle')) { $env:GRADLE_USER_HOME = 'D:\.gradle' }
function Add-Result([string]$Gate, [bool]$Passed, [string]$Detail) {
    $results.Add([pscustomobject]@{ gate = $Gate; passed = $Passed; detail = $Detail })
}
function Get-Sha256([string]$Path) {
    return ([System.Security.Cryptography.SHA256]::HashData([System.IO.File]::ReadAllBytes($Path)) | ForEach-Object { $_.ToString('x2') }) -join ''
}
function Run-Gradle([string[]]$Tasks) {
    $arguments = @()
    if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Java\jdk-21')) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not $env:GRADLE_USER_HOME -and (Test-Path 'D:\.gradle')) { $env:GRADLE_USER_HOME = 'D:\.gradle' }
    foreach ($task in $Tasks) { $arguments += $task }
    $arguments += '--console=plain'
    Push-Location $RepoRoot
    try {
        & (Join-Path $RepoRoot 'gradlew.bat') @arguments 2>&1 | ForEach-Object { Write-Host $_ }
        return ($LASTEXITCODE -eq 0)
    } finally { Pop-Location }
}

# ---- 1. Gradle gates ---------------------------------------------------------------------------
if (-not $SkipBuild) {
    $gradleOk = Run-Gradle $release.gradleTasks
    Add-Result 'gradle' $gradleOk (($release.gradleTasks -join ' '))
    if (-not $gradleOk) {
        Write-Output '  FAIL gradle  the Gradle gates did not complete'
        exit 1
    }
} else {
    Add-Result 'gradle' $true 'skipped (-SkipBuild)'
}

# ---- 2. JVM test results -----------------------------------------------------------------------
# One authoritative result directory per module: flavoured modules (app) use testLibreDebugUnitTest,
# plain modules use testDebugUnitTest. Release/stale result folders are deliberately ignored.
$testDirs = Get-ChildItem -LiteralPath $RepoRoot -Recurse -Directory -Filter 'test-results' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch 'androidTest' } | ForEach-Object {
        $preferred = Join-Path $_.FullName 'testLibreDebugUnitTest'
        $fallback = Join-Path $_.FullName 'testDebugUnitTest'
        if (Test-Path -LiteralPath $preferred) { $preferred } elseif (Test-Path -LiteralPath $fallback) { $fallback }
    } | Sort-Object -Unique
$testFiles = foreach ($dir in $testDirs) { Get-ChildItem -LiteralPath $dir -File -Filter 'TEST-*.xml' }
$tests = 0; $failures = 0; $errors = 0
foreach ($file in $testFiles) {
    $head = (Get-Content -LiteralPath $file.FullName -Raw) -split '</testsuite>' | Select-Object -First 1
    $m = [regex]::Match($head, 'tests="(\d+)"[^>]*?failures="(\d+)"[^>]*?errors="(\d+)"')
    if (-not $m.Success) { $m = [regex]::Match($head, 'tests="(\d+)"') }
    if ($m.Success) {
        $tests += [int]$m.Groups[1].Value
        if ($m.Groups.Count -ge 4) { $failures += [int]$m.Groups[2].Value; $errors += [int]$m.Groups[3].Value }
    }
}
$testsOk = ($tests -ge $release.jvmTestsMinimum -and $failures -eq 0 -and $errors -eq 0)
Add-Result 'jvm-tests' $testsOk "$tests tests, $failures failures, $errors errors, minimum $($release.jvmTestsMinimum)"

# ---- 3. Lint -----------------------------------------------------------------------------------
$lintPath = Join-Path $RepoRoot $release.lintReport
if (-not (Test-Path -LiteralPath $lintPath)) {
    Add-Result 'lint' $false "missing $($release.lintReport)"
} else {
    $lintXml = Get-Content -LiteralPath $lintPath -Raw
    $lintErrors = ([regex]::Matches($lintXml, 'severity="Error"')).Count
    $lintWarnings = ([regex]::Matches($lintXml, 'severity="Warning"')).Count
    Add-Result 'lint' ($lintErrors -eq 0) "$lintErrors errors, $lintWarnings warnings"
}

# ---- 4. Signed artifacts -----------------------------------------------------------------------
$artifactRecords = [System.Collections.Generic.List[object]]::new()
$buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
foreach ($artifact in $release.artifacts) {
    $path = Join-Path $RepoRoot $artifact.path
    if (-not (Test-Path -LiteralPath $path)) { Add-Result ('artifact-' + $artifact.id) $false 'missing'; continue }
    $size = (Get-Item -LiteralPath $path).Length
    $sha = Get-Sha256 $path
    $artifactOk = $true
    $detail = "$size B sha256=$sha"
    if ($null -ne $artifact.bytes -and [int64]$artifact.bytes -ne [int64]$size) {
        $artifactOk = $false
        $detail += '; size drift (recorded ' + [int64]$artifact.bytes + ' B)'
    }
    if ($artifact.sha256 -and ([string]$artifact.sha256).ToUpperInvariant() -ne $sha.ToUpperInvariant()) {
        $artifactOk = $false
        $detail += '; sha256 drift (recorded ' + [string]$artifact.sha256 + ')'
    }
    if ($artifact.format -eq 'apk' -and $buildTools) {
        $apksigner = Join-Path $buildTools[0].FullName 'apksigner.bat'
        $certificate = & $apksigner verify --print-certs $path 2>&1 | Out-String
        $signatureOk = ($LASTEXITCODE -eq 0)
        $digest = [regex]::Match($certificate, 'certificate SHA-256 digest: ([0-9a-fA-F]+)').Groups[1].Value
        $certOk = ($digest.ToUpperInvariant() -eq $release.signing.certificateSha256.ToUpperInvariant())
        $artifactOk = $signatureOk -and $certOk
        $detail += "; v2-signed=$signatureOk; cert=$digest"
    } elseif ($artifact.format -eq 'aab') {
        $jarsigner = Join-Path (Join-Path $env:JAVA_HOME 'bin') 'jarsigner.exe'
        $jarOut = & $jarsigner -verify $path 2>&1 | Out-String
        $artifactOk = ($LASTEXITCODE -eq 0 -and $jarOut -match 'jar verified')
        $detail += '; jarsigner=' + $artifactOk
    }
    Add-Result ('artifact-' + $artifact.id) $artifactOk $detail
    $artifactRecords.Add([pscustomobject]@{ id = $artifact.id; path = $artifact.path; bytes = $size; sha256 = $sha })
}

# ---- 5. Store assets ---------------------------------------------------------------------------
$validator = Join-Path $PSScriptRoot 'validate-store.ps1'
& $validator -RepoRoot $RepoRoot | ForEach-Object { Write-Host $_ }
Add-Result 'store-assets' ($LASTEXITCODE -eq 0) 'store listing + graphics + launch docs'

# ---- 6. Public site ----------------------------------------------------------------------------
$siteConfig = $release.publicSite
$siteBuilder = if ($siteConfig -and $siteConfig.buildScript) { Join-Path $RepoRoot $siteConfig.buildScript } else { Join-Path $PSScriptRoot 'build-site.ps1' }
$siteValidator = if ($siteConfig -and $siteConfig.validateScript) { Join-Path $RepoRoot $siteConfig.validateScript } else { Join-Path $PSScriptRoot 'validate-site.ps1' }
& $siteBuilder | ForEach-Object { Write-Host $_ }
Add-Result 'public-site-build' ($LASTEXITCODE -eq 0) 'website/dist rebuilt from website data'
& $siteValidator -RepoRoot $RepoRoot | ForEach-Object { Write-Host $_ }
Add-Result 'public-site' ($LASTEXITCODE -eq 0) 'bilingual site, SEO, policies, brand and drift checks'

# ---- 7. Device screenshot gate -----------------------------------------------------------------
if ($SkipEmulator) {
    Add-Result 'ui-regression' $true 'skipped (-SkipEmulator)'
} else {
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $devices = if (Test-Path -LiteralPath $adb) { & $adb devices 2>&1 | Out-String } else { '' }
    if ($devices -notmatch ($Serial + '\s+device')) {
        Add-Result 'ui-regression' $false "device $Serial is not online"
    } else {
        $uiScript = Join-Path $PSScriptRoot 'ui-regression.ps1'
        & $uiScript -Serial $Serial | ForEach-Object { Write-Host $_ }
        Add-Result 'ui-regression' ($LASTEXITCODE -eq 0) "4-screen dHash comparison on $Serial"
    }
}

# ---- 8. Report ---------------------------------------------------------------------------------
$report = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    version = (Select-String -LiteralPath (Join-Path $RepoRoot 'app/build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1).Matches[0].Groups[1].Value
    tests = [ordered]@{ total = $tests; failures = $failures; errors = $errors }
    artifacts = $artifactRecords
    results = $results
    passed = (($results | Where-Object { -not $_.passed }).Count -eq 0)
}
$reportPath = Join-Path $RepoRoot 'build/release-check.json'
New-Item -ItemType Directory -Path (Split-Path -Parent $reportPath) -Force | Out-Null
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Output "release check: version=$($report.version) tests=$tests failures=$failures errors=$errors"
foreach ($result in $results) { Write-Output (($(if ($result.passed) { '  PASS ' } else { '  FAIL ' })) + $result.gate + '  ' + $result.detail) }
Write-Output "report: $reportPath"
if (-not $report.passed) { exit 1 }
