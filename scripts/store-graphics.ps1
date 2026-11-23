#Requires -Version 7
<#
.SYNOPSIS
  Renders the store graphics (Play icon + feature graphic) from store/graphics/brand.json.

.DESCRIPTION
  The script is a generic renderer, not a drawing: every colour role, font, copy line, mark path and
  layout coordinate lives in store/graphics/brand.json. Palette roles mirror the shipped Girih & Ink
  theme pack and the mark is the shipped adaptive-icon path, both pinned by StoreGraphicsBrandTest.

  Arabic shaping is done by a real text engine: the page embeds the shipped Amiri / IBM Plex Sans Arabic
  fonts as data URIs and is screenshotted with headless Chrome (HarfBuzz). Project fonts are never
  installed on the machine, so the output is independent of the developer's font folder.

  Outputs: icon-512.png, feature-graphic-1024x500.png and manifest.json (sizes + SHA-256). Intermediate
  HTML and the throwaway Chrome profile go to store/graphics/.render/ (git-ignored).

.EXAMPLE
  ./scripts/store-graphics.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string]$BrandFile = '',
    [string]$OutDir = '',
    [string]$Browser = ''
)

$ErrorActionPreference = 'Stop'

if (-not $BrandFile) { $BrandFile = Join-Path $RepoRoot 'store/graphics/brand.json' }
if (-not $OutDir) { $OutDir = Join-Path $RepoRoot 'store/graphics' }
$renderDir = Join-Path $OutDir '.render'
$htmlDir = Join-Path $renderDir 'html'
New-Item -ItemType Directory -Force -Path $htmlDir | Out-Null

if (-not (Test-Path -LiteralPath $BrandFile -PathType Leaf)) { throw "brand file not found: $BrandFile" }
$brand = Get-Content -LiteralPath $BrandFile -Raw | ConvertFrom-Json
if ($brand.schemaVersion -ne 1) { throw "unsupported brand schemaVersion: $($brand.schemaVersion)" }

function Get-Color([string]$Role) {
    $value = $brand.palette.$Role
    if ([string]::IsNullOrWhiteSpace($value)) { throw "brand.json palette misses role '$Role'" }
    return $value
}

function Get-FontPath([string]$Role) {
    $relative = $brand.fonts.$Role
    if ([string]::IsNullOrWhiteSpace($relative)) { throw "brand.json fonts misses role '$Role'" }
    $path = Join-Path $RepoRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "font file not found: $path" }
    return $path
}

function Get-CopyLine([string]$Key) {
    $value = $brand.copy.$Key
    if ([string]::IsNullOrWhiteSpace($value)) { throw "brand.json copy misses key '$Key'" }
    return [System.Net.WebUtility]::HtmlEncode($value)
}

function Get-FontFaces([string[]]$Roles) {
    $faces = foreach ($role in $Roles) {
        $bytes = [System.IO.File]::ReadAllBytes((Get-FontPath $role))
        $uri = 'data:font/ttf;base64,' + [Convert]::ToBase64String($bytes)
        '@font-face{font-family:brand-' + $role + ';src:url(' + $uri + ') format("truetype");}'
    }
    return ($faces -join "`n")
}

function Get-MarkSvg([string]$FillRole, [int]$PathIndex = -1) {
    $viewport = $brand.mark.viewport
    $fills = Get-Color $FillRole
    $paths = @()
    $index = 0
    foreach ($path in $brand.mark.paths) {
        if ($PathIndex -ge 0 -and $index -ne $PathIndex) { $index++; continue }
        $paths += '<path d="' + $path.d + '" fill="' + $fills + '"/>'
        $index++
    }
    return '<svg viewBox="0 0 ' + $viewport + ' ' + $viewport + '" xmlns="http://www.w3.org/2000/svg" style="display:block;width:100%;height:100%">' + ($paths -join '') + '</svg>'
}

function Write-Html([string]$Name, [string]$Html) {
    $path = Join-Path $htmlDir $Name
    [System.IO.File]::WriteAllText($path, $Html, [System.Text.UTF8Encoding]::new($false))
    return $path
}

function Find-Browser([string]$Preferred) {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($Preferred)) { $candidates += $Preferred }
    $candidates += @(
        'C:\Program Files\Google\Chrome\Application\chrome.exe',
        'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
        'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
        'C:\Program Files\Microsoft\Edge\Application\msedge.exe'
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'No Chrome or Edge installation found; pass -Browser <path>.'
}

$script:browserPath = Find-Browser $Browser
$script:browserVersion = (Get-Item -LiteralPath $script:browserPath).VersionInfo.ProductVersion
Write-Output "renderer: $script:browserPath ($script:browserVersion)"

function Invoke-Render([string]$HtmlPath, [string]$OutPath, [int]$Width, [int]$Height) {
    if (Test-Path -LiteralPath $OutPath) { Remove-Item -LiteralPath $OutPath -Force }
    $profile = Join-Path $renderDir 'chrome-profile'
    # PowerShell's array-literal commas bind before concatenation, so build every argument first.
    $userDataArg = '--user-data-dir=' + $profile
    $screenshotArg = '--screenshot=' + $OutPath
    $windowSizeArg = '--window-size=' + $Width + ',' + $Height
    $urlArg = 'file:///' + ($HtmlPath -replace '\\', '/')
    $arguments = @(
        '--headless=new',
        '--disable-gpu',
        '--hide-scrollbars',
        '--force-device-scale-factor=1',
        '--no-first-run',
        $userDataArg,
        $screenshotArg,
        $windowSizeArg,
        $urlArg
    )
    & $script:browserPath @arguments 2>&1 | Out-Null
    $exitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $OutPath -PathType Leaf)) {
        throw "renderer produced no screenshot for $HtmlPath (exit $exitCode)"
    }
    Add-Type -AssemblyName System.Drawing
    $image = [System.Drawing.Image]::FromFile($OutPath)
    try {
        if ($image.Width -ne $Width -or $image.Height -ne $Height) {
            throw "$OutPath is $($image.Width)x$($image.Height), expected ${Width}x${Height}"
        }
    } finally {
        $image.Dispose()
    }
}

function New-OutputRecord([string]$File, [int]$Width, [int]$Height) {
    $path = Join-Path $OutDir $File
    $info = Get-Item -LiteralPath $path
    return [ordered]@{
        file = $File
        width = $Width
        height = $Height
        bytes = $info.Length
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

# --- icon -------------------------------------------------------------------------------------
$icon = $brand.outputs.icon
$iconWidth = [int]$icon.width
$iconHeight = [int]$icon.height
$iconHtml = '<!doctype html><html><head><meta charset="utf-8"><style>'
$iconHtml += 'html,body{margin:0;padding:0;width:' + $iconWidth + 'px;height:' + $iconHeight + 'px;overflow:hidden;background:' + (Get-Color $icon.background) + ';}'
$iconHtml += 'body>div{width:100%;height:100%;}'
$iconHtml += '</style></head><body><div>'
$iconHtml += (Get-MarkSvg $icon.mark.color)
$iconHtml += '</div></body></html>'
$iconHtmlPath = Write-Html 'icon.html' $iconHtml
$iconPath = Join-Path $OutDir $icon.file
Invoke-Render -HtmlPath $iconHtmlPath -OutPath $iconPath -Width $iconWidth -Height $iconHeight
Write-Output "icon: $($icon.file) ${iconWidth}x${iconHeight}"

# --- feature graphic --------------------------------------------------------------------------
$feature = $brand.outputs.featureGraphic
$featureWidth = [int]$feature.width
$featureHeight = [int]$feature.height
$usedFonts = @($feature.layers | Where-Object { $_.kind -eq 'text' } | ForEach-Object { $_.font } | Select-Object -Unique)
$featureHtml = '<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><style>'
$featureHtml += (Get-FontFaces $usedFonts)
$featureHtml += 'html,body{margin:0;padding:0;width:' + $featureWidth + 'px;height:' + $featureHeight + 'px;overflow:hidden;background:' + (Get-Color $feature.background) + ';}'
$featureHtml += 'body>div.layer{position:absolute;}'
$featureHtml += '</style></head><body>'
foreach ($layer in $feature.layers) {
    switch ($layer.kind) {
        'markOutline' {
            $featureHtml += '<svg style="position:absolute;left:' + $layer.left + 'px;top:' + $layer.top + 'px;width:' + $layer.size + 'px;height:' + $layer.size + 'px;opacity:' + $layer.opacity + ';" viewBox="0 0 ' + $brand.mark.viewport + ' ' + $brand.mark.viewport + '" xmlns="http://www.w3.org/2000/svg">'
            $index = 0
            foreach ($path in $brand.mark.paths) {
                if ($layer.PSObject.Properties.Name -contains 'pathIndex' -and $index -ne [int]$layer.pathIndex) { $index++; continue }
                $featureHtml += '<path d="' + $path.d + '" fill="none" stroke="' + (Get-Color $layer.color) + '" stroke-width="' + $layer.strokeWidth + '" vector-effect="non-scaling-stroke" stroke-linejoin="round"/>'
                $index++
            }
            $featureHtml += '</svg>'
        }
        'tile' {
            $featureHtml += '<div style="position:absolute;left:' + $layer.left + 'px;top:' + $layer.top + 'px;width:' + $layer.size + 'px;height:' + $layer.size + 'px;border-radius:' + $layer.radius + 'px;background:' + (Get-Color $layer.background) + ';overflow:hidden;">' + (Get-MarkSvg $layer.markColor) + '</div>'
        }
        'rule' {
            $featureHtml += '<div style="position:absolute;top:' + $layer.top + 'px;' + $(if ($layer.PSObject.Properties.Name -contains 'right') { 'right:' + $layer.right + 'px;' } else { 'left:' + $layer.left + 'px;' }) + 'width:' + $layer.width + 'px;height:' + $layer.height + 'px;background:' + (Get-Color $layer.color) + ';"></div>'
        }
        'text' {
            $css = 'position:absolute;top:' + $layer.top + 'px;font-family:brand-' + $layer.font + ';font-size:' + $layer.size + 'px;color:' + (Get-Color $layer.color) + ';line-height:' + $layer.lineHeight + ';white-space:nowrap;'
            if ($layer.PSObject.Properties.Name -contains 'align' -and $layer.align -eq 'center') {
                $css += 'left:' + $layer.left + 'px;width:' + $layer.width + 'px;text-align:center;'
            } elseif ($layer.PSObject.Properties.Name -contains 'right') {
                $css += 'right:' + $layer.right + 'px;'
            } else {
                $css += 'left:' + $layer.left + 'px;'
            }
            if ($layer.PSObject.Properties.Name -contains 'letterSpacing') { $css += 'letter-spacing:' + $layer.letterSpacing + 'px;' }
            if ($layer.PSObject.Properties.Name -contains 'direction') { $css += 'direction:' + $layer.direction + ';text-align:left;' }
            $featureHtml += '<div style="' + $css + '">' + (Get-CopyLine $layer.textKey) + '</div>'
        }
        default { throw "unknown feature layer kind: $($layer.kind)" }
    }
}
$featureHtml += '</body></html>'
$featureHtmlPath = Write-Html 'feature-graphic.html' $featureHtml
$featurePath = Join-Path $OutDir $feature.file
Invoke-Render -HtmlPath $featureHtmlPath -OutPath $featurePath -Width $featureWidth -Height $featureHeight
Write-Output "feature graphic: $($feature.file) ${featureWidth}x${featureHeight}"

# --- manifest ---------------------------------------------------------------------------------
$manifest = [ordered]@{
    schemaVersion = 1
    generatedBy = 'scripts/store-graphics.ps1'
    renderer = [ordered]@{ name = (Split-Path -Leaf $script:browserPath); mode = 'headless --screenshot'; version = $script:browserVersion }
    sources = [ordered]@{
        brand = 'store/graphics/brand.json'
        brandSha256 = (Get-FileHash -LiteralPath $BrandFile -Algorithm SHA256).Hash.ToLowerInvariant()
        mark = 'app/src/main/res/drawable/ic_launcher_foreground.xml'
        palette = $brand.provenance.palette
    }
    outputs = @(
        (New-OutputRecord -File $icon.file -Width $iconWidth -Height $iconHeight),
        (New-OutputRecord -File $feature.file -Width $featureWidth -Height $featureHeight)
    )
}
$manifestPath = Join-Path $OutDir 'manifest.json'
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8) + "`n", [System.Text.UTF8Encoding]::new($false))

foreach ($output in $manifest.outputs) {
    Write-Output ('  ' + $output.file + ' ' + $output.width + 'x' + $output.height + ' ' + $output.bytes + ' bytes sha256=' + $output.sha256)
}
Write-Output "manifest: $manifestPath"
