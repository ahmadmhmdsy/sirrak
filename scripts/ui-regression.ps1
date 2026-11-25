<#
.SYNOPSIS
  Screenshot regression smoke test for Sirrak on a connected emulator/device.

.DESCRIPTION
  Captures a fixed set of screens from a clean launch, crops the status bar and the
  navigation bar out, and compares a perceptual dHash of each screen with the checked-in
  baseline in scripts/ui-baseline/. The comparison tolerates anti-aliasing and the system
  clock (both are cropped out or absorbed by the 8x8 hash) but fails when layout, colour or
  content of a screen actually changes.

  This is the device half of the P3.6 visual gate: GitHub CI cannot run it (no emulator in
  the workflow), so it runs locally against the Nawa_Phone AVD before a release cut, and
  the baseline is committed with a short provenance note in scripts/README.md.

.EXAMPLE
  pwsh -File scripts/ui-regression.ps1                # compare against the baseline
  pwsh -File scripts/ui-regression.ps1 -UpdateBaseline # accept the current screens as new baseline
#>

param(
    [string]$Serial = "emulator-5554",
    [int]$MaxDistance = 8,
    [switch]$UpdateBaseline,
    [int]$BootTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$Root = Split-Path -Parent $PSScriptRoot
$Adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools/adb.exe" } else { "adb" }
$BaselineDir = Join-Path $PSScriptRoot "ui-baseline"
$CurrentDir = Join-Path $PSScriptRoot "ui-current"
$BaselineFile = Join-Path $BaselineDir "baseline.json"
$StatusBarHeight = 110
$NavBarHeight = 150

function Invoke-Adb {
    param([string[]]$Arguments)
    $out = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ("adb {0} failed: {1}" -f ($Arguments -join " "), ($out -join " ")) }
    return ($out -join "`n")
}

function Save-Screenshot {
    param([string]$Path)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Adb
    $psi.Arguments = "-s $Serial exec-out screencap -p"
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stream = [System.IO.File]::Create($Path)
    try { $proc.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) { throw "screencap failed with exit code $($proc.ExitCode)" }
}

function Get-ScreenHash {
    param([string]$Path)
    $image = [System.Drawing.Bitmap]::FromFile($Path)
    try {
        $cropHeight = $image.Height - $StatusBarHeight - $NavBarHeight
        if ($cropHeight -le 0) { throw "screenshot is too small to crop: $($image.Width)x$($image.Height)" }
        $crop = [System.Drawing.Bitmap]::new($image.Width, $cropHeight)
        $graphics = [System.Drawing.Graphics]::FromImage($crop)
        try {
            $dest = [System.Drawing.Rectangle]::new(0, 0, $crop.Width, $crop.Height)
            $src = [System.Drawing.Rectangle]::new(0, $StatusBarHeight, $image.Width, $cropHeight)
            $graphics.DrawImage($image, $dest, $src, [System.Drawing.GraphicsUnit]::Pixel)
        } finally { $graphics.Dispose() }
        $small = [System.Drawing.Bitmap]::new(9, 8)
        $smallGraphics = [System.Drawing.Graphics]::FromImage($small)
        try {
            $smallGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBilinear
            $smallGraphics.DrawImage($crop, 0, 0, 9, 8)
        } finally { $smallGraphics.Dispose(); $crop.Dispose() }
        [UInt64]$hash = 0
        for ($y = 0; $y -lt 8; $y++) {
            for ($x = 0; $x -lt 8; $x++) {
                $left = $small.GetPixel($x, $y)
                $right = $small.GetPixel($x + 1, $y)
                $leftLuma = 0.299 * $left.R + 0.587 * $left.G + 0.114 * $left.B
                $rightLuma = 0.299 * $right.R + 0.587 * $right.G + 0.114 * $right.B
                if ($leftLuma -gt $rightLuma) { $hash = $hash -bor ([UInt64]1 -shl ($y * 8 + $x)) }
            }
        }
        $small.Dispose()
        return ("0x{0:X16}" -f $hash)
    } finally { $image.Dispose() }
}

function Get-HammingDistance {
    param([UInt64]$A, [UInt64]$B)
    [UInt64]$x = $A -bxor $B
    $distance = 0
    while ($x -ne 0) { $distance += [int]($x -band 1); $x = $x -shr 1 }
    return $distance
}

function Tap-Scaled {
    param([double]$FractionX, [double]$FractionY)
    $x = [int]($script:ScreenWidth * $FractionX)
    $y = [int]($script:ScreenHeight * $FractionY)
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
}

Write-Host "Serial: $Serial"
$size = Invoke-Adb @("shell", "wm", "size")
$match = [regex]::Match($size, "([0-9]+)x([0-9]+)")
if (-not $match.Success) { throw "could not read screen size: $size" }
$script:ScreenWidth = [int]$match.Groups[1].Value
$script:ScreenHeight = [int]$match.Groups[2].Value
Write-Host "Screen: $script:ScreenWidth x $script:ScreenHeight"

if ($UpdateBaseline) {
    New-Item -ItemType Directory -Force -Path $BaselineDir | Out-Null
} else {
    if (-not (Test-Path $BaselineFile)) { throw "missing baseline $BaselineFile; run with -UpdateBaseline once" }
    $baseline = Get-Content $BaselineFile -Raw | ConvertFrom-Json
}
New-Item -ItemType Directory -Force -Path $CurrentDir | Out-Null

$screens = @(
    @{ Name = "home"; Capture = { Save-Screenshot (Join-Path $CurrentDir "home.png") } },
    @{ Name = "model-picker"; Capture = {
        # The engine chip moved into the wordmark row when the model manager left Settings; its
        # centre is now at y=0.166 of the frame (418,212 on a 720x1280 device).
        Tap-Scaled 0.64 0.166 | Out-Null
        Start-Sleep -Seconds 2
        Save-Screenshot (Join-Path $CurrentDir "model-picker.png")
        Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
    } },
    @{ Name = "library-empty"; Capture = {
        Tap-Scaled 0.50 0.91 | Out-Null
        Start-Sleep -Seconds 2
        Save-Screenshot (Join-Path $CurrentDir "library-empty.png")
    } },
    @{ Name = "settings-top"; Capture = {
        Tap-Scaled 0.16 0.91 | Out-Null
        Start-Sleep -Seconds 2
        Save-Screenshot (Join-Path $CurrentDir "settings-top.png")
    } }
)

Write-Host "Launching app from a clean state..."
Invoke-Adb @("shell", "am", "force-stop", "app.sirrak") | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", "app.sirrak/.MainActivity") | Out-Null
Start-Sleep -Seconds 4

$results = @()
foreach ($screen in $screens) {
    & $screen.Capture
    Start-Sleep -Milliseconds 400
    $hash = Get-ScreenHash (Join-Path $CurrentDir ($screen.Name + ".png"))
    $results += [pscustomobject]@{ Name = $screen.Name; Hash = $hash }
}

if ($UpdateBaseline) {
    foreach ($result in $results) {
        Copy-Item (Join-Path $CurrentDir ($result.Name + ".png")) (Join-Path $BaselineDir ($result.Name + ".png")) -Force
    }
    $payload = [pscustomobject]@{
        generatedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        device = $Serial
        screen = "$script:ScreenWidth x $script:ScreenHeight"
        note = "Captured from a clean launch; status bar and nav bar are cropped before hashing."
        screens = $results
    }
    $payload | ConvertTo-Json -Depth 5 | Set-Content -Path $BaselineFile -Encoding utf8
    Write-Host "Baseline updated: $BaselineFile"
    exit 0
}

$failed = 0
foreach ($result in $results) {
    $expected = $baseline.screens | Where-Object { $_.Name -eq $result.Name }
    if (-not $expected) { Write-Host ("MISSING  {0}" -f $result.Name); $failed++; continue }
    $expectedHash = [Convert]::ToUInt64(($expected.Hash -replace "^0x", ""), 16)
    $actualHash = [Convert]::ToUInt64(($result.Hash -replace "^0x", ""), 16)
    $distance = Get-HammingDistance $expectedHash $actualHash
    if ($distance -le $MaxDistance) {
        Write-Host ("PASS     {0}  (distance {1})" -f $result.Name, $distance)
    } else {
        Write-Host ("FAIL     {0}  (distance {1} > {2}; current capture: scripts/ui-current/{0}.png)" -f $result.Name, $distance, $MaxDistance)
        $failed++
    }
}

if ($failed -gt 0) {
    Write-Host ""
    Write-Host "Screenshot regression: $failed screen(s) changed. Review scripts/ui-current/, then"
    Write-Host "rerun with -UpdateBaseline if the change is intended."
    exit 1
}
Write-Host "Screenshot regression: all $($results.Count) screens match the baseline."
exit 0
