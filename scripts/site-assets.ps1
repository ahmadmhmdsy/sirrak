#requires -Version 7.0
<#
  Generates the committed website raster assets from the canonical brand data:
    store/graphics/icon-512.png  ->  website/assets/{icon-512,icon-192,apple-touch-icon,favicon-32}.png
    store/graphics/brand.json    ->  website/assets/og-image.png (1200x630, rendered by headless Chrome)

  Dev-only tool: Windows + Chrome + System.Drawing. CI and the site build consume the
  committed outputs in website/assets/, so the build itself stays portable.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$siteRoot = Join-Path $repoRoot 'website'
$assetsDir = Join-Path $siteRoot 'assets'
$renderDir = Join-Path $siteRoot '.render'
$fontRenderDir = Join-Path $renderDir 'fonts'

Add-Type -AssemblyName System.Drawing

function Read-JsonFile([string]$relPath) {
    $full = Join-Path $repoRoot $relPath
    if (-not (Test-Path -LiteralPath $full)) { throw "missing input: $full" }
    return (Get-Content -LiteralPath $full -Raw -Encoding utf8 | ConvertFrom-Json)
}

$site = Read-JsonFile 'website/site.json'
$brand = Read-JsonFile 'store/graphics/brand.json'

foreach ($dir in @($assetsDir, $renderDir, $fontRenderDir)) {
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
}

function Find-Chrome {
    $candidates = @()
    if ($env:CHROME_PATH) { $candidates += $env:CHROME_PATH }
    $candidates += 'C:\Program Files\Google\Chrome\Application\chrome.exe'
    $candidates += 'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe'
    $candidates += (Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    throw 'Chrome not found. Set CHROME_PATH to chrome.exe (this script is dev-only; CI uses committed assets).'
}

# ---------------------------------------------------------------------------
# Icons from the canonical 512px source
# ---------------------------------------------------------------------------
$iconSource = Join-Path $repoRoot 'store/graphics/icon-512.png'
if (-not (Test-Path -LiteralPath $iconSource)) { throw "missing brand icon: $iconSource" }
Copy-Item -LiteralPath $iconSource -Destination (Join-Path $assetsDir 'icon-512.png') -Force

function New-ResizedPng([string]$sourcePath, [string]$targetPath, [int]$size) {
    $source = [System.Drawing.Image]::FromFile($sourcePath)
    try {
        $bitmap = New-Object System.Drawing.Bitmap($size, $size)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.Clear([System.Drawing.Color]::Transparent)
                $graphics.DrawImage($source, 0, 0, $size, $size)
            } finally { $graphics.Dispose() }
            $bitmap.Save($targetPath, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $bitmap.Dispose() }
    } finally { $source.Dispose() }
    $check = [System.Drawing.Image]::FromFile($targetPath)
    try {
        if ($check.Width -ne $size -or $check.Height -ne $size) {
            throw ('resize produced {0}x{1}, expected {2}x{2}: {3}' -f $check.Width, $check.Height, $size, $targetPath)
        }
    } finally { $check.Dispose() }
}

New-ResizedPng $iconSource (Join-Path $assetsDir 'icon-192.png') 192
New-ResizedPng $iconSource (Join-Path $assetsDir 'apple-touch-icon.png') 180
New-ResizedPng $iconSource (Join-Path $assetsDir 'favicon-32.png') 32

# ---------------------------------------------------------------------------
# Open Graph card: 1200x630, brand data only, rendered locally
# ---------------------------------------------------------------------------
$phoneSource = Join-Path $repoRoot 'store/screenshots/01-home-ar.png'
if (-not (Test-Path -LiteralPath $phoneSource)) { throw "missing hero screenshot: $phoneSource" }
Copy-Item -LiteralPath $phoneSource -Destination (Join-Path $renderDir 'phone-render.png') -Force

$fontPairs = @(
    @{ source = [string]$brand.fonts.displayBold; name = 'amiri-bold.ttf' },
    @{ source = [string]$brand.fonts.displayRegular; name = 'amiri-regular.ttf' },
    @{ source = [string]$brand.fonts.sansSemibold; name = 'plex-arabic-semibold.ttf' },
    @{ source = [string]$brand.fonts.sansRegular; name = 'plex-arabic-regular.ttf' }
)
foreach ($font in $fontPairs) {
    Copy-Item -LiteralPath (Join-Path $repoRoot $font.source) -Destination (Join-Path $fontRenderDir $font.name) -Force
}

$pathMarkup = ''
foreach ($pathItem in @($brand.mark.paths)) {
    $pathMarkup += ('<path d="{0}" />' -f [string]$pathItem.d)
}
$viewBox = '0 0 {0} {0}' -f [int]$brand.mark.viewport
$markSvg = ('<svg class="mark" viewBox="{0}" fill="none" stroke="{1}" stroke-width="4" stroke-linejoin="round" stroke-linecap="round">{2}</svg>' -f $viewBox, [string]$brand.palette.lapis, $pathMarkup)

$template = @'
<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<title>Sirrak OG card</title>
<style>
@font-face { font-family: "Amiri"; src: url("fonts/amiri-bold.ttf") format("truetype"); font-weight: 700; }
@font-face { font-family: "Amiri"; src: url("fonts/amiri-regular.ttf") format("truetype"); font-weight: 400; }
@font-face { font-family: "Plex"; src: url("fonts/plex-arabic-regular.ttf") format("truetype"); font-weight: 400; }
@font-face { font-family: "Plex"; src: url("fonts/plex-arabic-semibold.ttf") format("truetype"); font-weight: 600; }
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { width: 1200px; height: 630px; overflow: hidden; }
body {
  background: {{GROUND}};
  color: {{INK}};
  font-family: "Plex", sans-serif;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  padding: 40px 56px 34px;
  position: relative;
}
body::before {
  content: ""; position: absolute; inset: 0;
  background-image: linear-gradient(to left, {{HAIRLINE}} 1px, transparent 1px);
  background-size: calc(100% / 12) 100%;
  opacity: .5; pointer-events: none;
}
.top { display: flex; align-items: center; gap: 16px; position: relative; }
.mark { width: 60px; height: 60px; flex: none; }
.top .latin { font-size: 14px; font-weight: 600; letter-spacing: 0.46em; color: {{MUTED}}; }
.version { margin-inline-start: auto; font-size: 15px; font-weight: 600; color: {{MUTED}}; }
.middle { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 40px; padding-block: 6px; }
.text { min-width: 0; }
.wordmark { font-family: "Amiri"; font-weight: 700; font-size: 142px; line-height: 1.1; color: {{LAPIS}}; }
.tagline { font-family: "Amiri"; font-weight: 400; font-size: 38px; line-height: 1.48; color: {{INK}}; margin-top: 10px; }
.device { border: 1px solid {{INK}}; border-radius: 26px; padding: 6px; background: {{SURFACE}}; box-shadow: 0 18px 40px -26px rgba(27, 36, 48, .5); }
.device img { display: block; height: 386px; width: auto; border-radius: 20px; }
.bottom { position: relative; display: flex; align-items: baseline; justify-content: space-between; gap: 24px; border-top: 2px solid {{BRASS}}; padding-top: 14px; }
.meta { font-size: 16px; font-weight: 600; color: {{MUTED}}; }
.privacy { font-family: "Amiri"; font-weight: 700; font-size: 20px; color: {{INK}}; }
</style>
</head>
<body>
  <div class="top">{{MARK}}<span class="latin">SIRRAK</span><span class="version">v{{VERSION}}</span></div>
  <div class="middle">
    <div class="text">
      <div class="wordmark">{{WORDMARK}}</div>
      <div class="tagline">{{TAGLINE1}}<br>{{TAGLINE2}}</div>
    </div>
    <div class="device"><img src="phone-render.png" alt=""></div>
  </div>
  <div class="bottom">
    <span class="meta">{{MINANDROID}} · Apache-2.0 · github.com/ahmadmhmdsy/sirrak · libre APK</span>
    <span class="privacy">{{PRIVACYLINE}}</span>
  </div>
</body>
</html>
'@

$ogHtml = $template
$ogHtml = $ogHtml.Replace('{{GROUND}}', [string]$brand.palette.ground)
$ogHtml = $ogHtml.Replace('{{INK}}', [string]$brand.palette.ink)
$ogHtml = $ogHtml.Replace('{{MUTED}}', [string]$brand.palette.muted)
$ogHtml = $ogHtml.Replace('{{LAPIS}}', [string]$brand.palette.lapis)
$ogHtml = $ogHtml.Replace('{{BRASS}}', [string]$brand.palette.brass)
$ogHtml = $ogHtml.Replace('{{SURFACE}}', [string]$brand.palette.surface)
$ogHtml = $ogHtml.Replace('{{HAIRLINE}}', [string]$brand.palette.hairline)
$ogHtml = $ogHtml.Replace('{{MARK}}', $markSvg)
$ogHtml = $ogHtml.Replace('{{WORDMARK}}', [string]$brand.copy.arabicWordmark)
$ogHtml = $ogHtml.Replace('{{TAGLINE1}}', [string]$brand.copy.taglineLine1)
$ogHtml = $ogHtml.Replace('{{TAGLINE2}}', [string]$brand.copy.taglineLine2)
$ogHtml = $ogHtml.Replace('{{PRIVACYLINE}}', [string]$brand.copy.privacyLine)
$ogHtml = $ogHtml.Replace('{{VERSION}}', [string]$site.product.version)
$ogHtml = $ogHtml.Replace('{{MINANDROID}}', [string]$site.minAndroid)
$ogPath = Join-Path $renderDir 'og.html'
[System.IO.File]::WriteAllText($ogPath, $ogHtml, [System.Text.UTF8Encoding]::new($false))

$chrome = Find-Chrome
$rawShot = Join-Path $renderDir 'og-raw.png'
if (Test-Path -LiteralPath $rawShot) { Remove-Item -LiteralPath $rawShot -Force }
$chromeArgs = @(
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    '--window-size=1200,630',
    '--allow-file-access-from-files',
    '--virtual-time-budget=5000',
    ('--screenshot=' + $rawShot),
    ('file:///' + ($ogPath -replace '\\', '/'))
)
& $chrome @chromeArgs 2>$null | Out-Null
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    if (Test-Path -LiteralPath $rawShot) { break }
    Start-Sleep -Milliseconds 250
}
if (-not (Test-Path -LiteralPath $rawShot)) { throw "Chrome did not produce $rawShot" }

$rawImage = [System.Drawing.Image]::FromFile($rawShot)
try {
    $width = $rawImage.Width
    $height = $rawImage.Height
} finally { $rawImage.Dispose() }
if ($width -ne 1200 -or $height -ne 630) { throw ('OG render is {0}x{1}, expected 1200x630' -f $width, $height) }
Copy-Item -LiteralPath $rawShot -Destination (Join-Path $assetsDir 'og-image.png') -Force

$summary = [ordered]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    chrome = $chrome
    ogImage = 'website/assets/og-image.png'
    ogSize = '1200x630'
    icons = @('icon-512.png', 'icon-192.png', 'apple-touch-icon.png', 'favicon-32.png')
}
$summaryJson = ConvertTo-Json -InputObject $summary -Depth 4
$logPath = Join-Path $renderDir 'site-assets.json'
[System.IO.File]::WriteAllText($logPath, $summaryJson, [System.Text.UTF8Encoding]::new($false))
Write-Output ('site assets written to ' + $assetsDir)
Get-ChildItem -LiteralPath $assetsDir -File | ForEach-Object { Write-Output ('  ' + $_.Name + '  ' + $_.Length + ' bytes') }
