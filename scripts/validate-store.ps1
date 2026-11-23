<#
.SYNOPSIS
    Validates the Play/F-Droid store assets against store/store-spec.json before upload.

.DESCRIPTION
    Data-driven launch gate for the store folder: Play character limits, required listing files,
    listing-name consistency with the shipped app name, What's-new version presence, screenshot
    count/size/aspect, store graphics dimensions + manifest hashes, and the presence of every
    launch document. Limits, languages and required files all live in store/store-spec.json, so
    changing a platform limit is a data edit, not a script edit.

.EXAMPLE
    ./scripts/validate-store.ps1
    ./scripts/validate-store.ps1 -Json | ConvertFrom-Json
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SpecFile,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
if (-not $SpecFile) { $SpecFile = Join-Path $RepoRoot 'store/store-spec.json' }
if (-not (Test-Path -LiteralPath $SpecFile)) { throw "store spec not found: $SpecFile" }

$spec = Get-Content -LiteralPath $SpecFile -Raw | ConvertFrom-Json
$storeDir = Join-Path $RepoRoot 'store'
$checks = 0
$failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$Message) { $script:failures.Add($Message) }
function Assert-Check([bool]$Ok, [string]$Message) {
    $script:checks++
    if (-not $Ok) { Add-Failure $Message }
}

function Read-TextFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    return (Get-Content -LiteralPath $Path -Raw)
}

function Get-CharCount([string]$Text) { return $Text.Length }

function Get-PngSize([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 24 -or $bytes[0] -ne 0x89 -or $bytes[1] -ne 0x50) { return $null }
    $width = ([int]$bytes[16] * 16777216) + ([int]$bytes[17] * 65536) + ([int]$bytes[18] * 256) + [int]$bytes[19]
    $height = ([int]$bytes[20] * 16777216) + ([int]$bytes[21] * 65536) + ([int]$bytes[22] * 256) + [int]$bytes[23]
    return [pscustomobject]@{ Width = $width; Height = $height }
}

function Get-Sha256([string]$Path) {
    return ([System.Security.Cryptography.SHA256]::HashData([System.IO.File]::ReadAllBytes($Path)) | ForEach-Object { $_.ToString('x2') }) -join ''
}

# ---- Play limits and listing text -------------------------------------------------------------
$version = $null
$versionSource = Read-TextFile (Join-Path $RepoRoot $spec.versionSource)
if ($null -ne $versionSource) {
    $match = [regex]::Match($versionSource, 'versionName\s*=\s*"([^"]+)"')
    if ($match.Success) { $version = $match.Groups[1].Value }
}
Assert-Check ($null -ne $version) "cannot read versionName from $($spec.versionSource)"

foreach ($language in $spec.languages) {
    $dir = Join-Path $storeDir ('listing/' + $language.dir)
    foreach ($key in @('name', 'shortDescription', 'fullDescription', 'whatsNew')) {
        $fileName = $language.files.$key
        $path = Join-Path $dir $fileName
        $text = Read-TextFile $path
        if ($null -eq $text) { Add-Failure "[$($language.code)] missing $($language.dir)/$fileName"; continue }
        $raw = $text
        $trimmed = $text.Trim()
        $body = $text.TrimEnd("`r", "`n")
        Assert-Check ($body -eq $body.Trim()) "[$($language.code)] $fileName has leading or trailing whitespace"
        Assert-Check ($trimmed.Length -gt 0) "[$($language.code)] $fileName is empty"
        Assert-Check (-not ($trimmed.Contains("`t"))) "[$($language.code)] $fileName contains a tab"
        $limit = if ($key -eq 'name') { $spec.limits.name } elseif ($key -eq 'shortDescription') { $spec.limits.shortDescription } elseif ($key -eq 'fullDescription') { $spec.limits.fullDescription } else { $spec.limits.whatsNew }
        Assert-Check ($trimmed.Length -le $limit) "[$($language.code)] $fileName is $($trimmed.Length) chars, Play allows $limit"
    }
    $namePath = Join-Path $dir $language.files.name
    $listingName = Read-TextFile $namePath
    if ($null -ne $listingName) {
        $source = $spec.nameSources.($language.code)
        if ($null -ne $source -and $source.StartsWith('literal:')) {
            Assert-Check ($listingName.Trim() -eq $source.Substring(8)) "[$($language.code)] listing name '$($listingName.Trim())' must be '$($source.Substring(8))'"
        } elseif ($null -ne $source -and $source.StartsWith('strings:')) {
            $resource = $source.Substring(8)
            $stringsPath = Join-Path $RepoRoot 'app/src/main/res/values/strings.xml'
            $strings = Read-TextFile $stringsPath
            if ($null -eq $strings) { Add-Failure "[$($language.code)] missing app strings.xml" } else {
                $m = [regex]::Match($strings, '<string name="' + [regex]::Escape($resource) + '">([^<]+)</string>')
                Assert-Check $m.Success "[$($language.code)] name resource $resource not found in app strings.xml"
                if ($m.Success) { Assert-Check ($m.Groups[1].Value -eq $listingName.Trim()) "[$($language.code)] listing name '$($listingName.Trim())' must equal shipped $resource '$($m.Groups[1].Value)'" }
            }
        }
        if ($spec.requireVersionInWhatsNew) {
            $whatsNew = Read-TextFile (Join-Path $dir $language.files.whatsNew)
            if ($null -ne $whatsNew) { Assert-Check ($whatsNew.Contains($version)) "[$($language.code)] What's new must mention the current version $version" }
        }
    }
}

# ---- Screenshots ------------------------------------------------------------------------------
$shotsDir = Join-Path $storeDir $spec.screenshots.dir
$shots = if (Test-Path -LiteralPath $shotsDir) {
    Get-ChildItem -LiteralPath $shotsDir -File | Where-Object { $_.Extension -eq $spec.screenshots.extension }
} else { @() }
Assert-Check ($shots.Count -ge $spec.screenshots.min -and $shots.Count -le $spec.screenshots.max) "screenshots: found $($shots.Count), Play allows $($spec.screenshots.min)-$($spec.screenshots.max)"
foreach ($shot in $shots) {
    $size = Get-PngSize $shot.FullName
    if ($null -eq $size) { Add-Failure "screenshots/$($shot.Name) is not a readable PNG"; continue }
    Assert-Check ($size.Width -ge $spec.screenshots.minSide -and $size.Height -ge $spec.screenshots.minSide) "screenshots/$($shot.Name) is $($size.Width)x$($size.Height), min side is $($spec.screenshots.minSide)"
    Assert-Check ($size.Width -le $spec.screenshots.maxSide -and $size.Height -le $spec.screenshots.maxSide) "screenshots/$($shot.Name) is $($size.Width)x$($size.Height), max side is $($spec.screenshots.maxSide)"
    if ($spec.screenshots.orientation -eq 'portrait') { Assert-Check ($size.Height -gt $size.Width) "screenshots/$($shot.Name) must be portrait" }
}

# ---- Store graphics ---------------------------------------------------------------------------
$graphicsDir = Join-Path $storeDir 'graphics'
$manifestPath = Join-Path $storeDir $spec.graphics.manifest
$manifestText = Read-TextFile $manifestPath
if ($null -eq $manifestText) { Add-Failure 'graphics manifest missing' } else {
    $manifest = $manifestText | ConvertFrom-Json
    $brandPath = Join-Path $storeDir $manifest.sources.brand
    if (Test-Path -LiteralPath $brandPath) {
        Assert-Check ((Get-Sha256 $brandPath) -eq $manifest.sources.brandSha256) 'graphics manifest is stale: re-run scripts/store-graphics.ps1'
    }
    foreach ($required in $spec.graphics.required) {
        $file = Join-Path $graphicsDir $required.file
        if (-not (Test-Path -LiteralPath $file)) { Add-Failure "graphics/$($required.file) missing"; continue }
        $size = Get-PngSize $file
        $entry = $manifest.outputs | Where-Object { $_.file -eq $required.file } | Select-Object -First 1
        Assert-Check ($null -ne $entry) "graphics/$($required.file) missing from manifest"
        Assert-Check ($size.Width -eq $required.width -and $size.Height -eq $required.height) "graphics/$($required.file) is $($size.Width)x$($size.Height), expected $($required.width)x$($required.height)"
        if ($null -ne $entry) {
            Assert-Check ((Get-Sha256 $file) -eq $entry.sha256) "graphics/$($required.file) hash differs from manifest"
            Assert-Check ($size.Width -eq $entry.width -and $size.Height -eq $entry.height) "graphics/$($required.file) manifest dimensions are stale"
        }
    }
}

# ---- Launch documents -------------------------------------------------------------------------
foreach ($required in $spec.requiredFiles) {
    Assert-Check (Test-Path -LiteralPath (Join-Path $storeDir $required)) "required launch file missing: store/$required"
}

# ---- Report -----------------------------------------------------------------------------------
$result = [ordered]@{
    spec = $SpecFile
    version = $version
    checks = $checks
    passed = $failures.Count -eq 0
    failures = $failures
}
if ($Json) {
    $result | ConvertTo-Json -Depth 4
} else {
    Write-Output "store validation: version=$version, checks=$checks"
    foreach ($failure in $failures) { Write-Output ('  FAIL ' + $failure) }
    if ($failures.Count -eq 0) { Write-Output '  PASS all store assets are launch-ready' }
}
if ($failures.Count -gt 0) { exit 1 }
