#requires -Version 7.0
<#
  validate-site.ps1 -- structural, SEO, accessibility, honesty and drift checks for the
  generated public website (website/dist) and its data sources (website/site.json,
  website/content/*.json, website/theme/site.css, store/graphics/brand.json).

  Read-only: it never rebuilds and never writes. Run from anywhere:

      pwsh -NoProfile -File scripts/validate-site.ps1

  Exit code 0 = every check passed. Exit code 1 = at least one check failed.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing | Out-Null

$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot)
$DistRoot = Join-Path $RepoRoot 'website/dist'
$script:checks = 0
$script:failures = New-Object 'System.Collections.Generic.List[string]'

function Section([string]$Title) {
    if (-not $Quiet) {
        Write-Host ''
        Write-Host ('== ' + $Title + ' ' + ('=' * [Math]::Max(0, 68 - $Title.Length)))
    }
}

function Check([string]$Name, [bool]$Condition, [string]$Detail = '') {
    $script:checks++
    if ($Condition) {
        if (-not $Quiet) { Write-Host ('  PASS  ' + $Name) }
        return
    }
    $entry = $Name
    if ($Detail) { $entry = $entry + '  <-- ' + $Detail }
    $script:failures.Add($entry)
    Write-Host ('  FAIL  ' + $entry) -ForegroundColor Red
}

function Read-RepoText([string]$RelativePath) {
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    return [System.IO.File]::ReadAllText($path)
}

function Test-RepoFile([string]$RelativePath) {
    return (Test-Path -LiteralPath (Join-Path $RepoRoot $RelativePath) -PathType Leaf)
}

function Get-FirstGroup([string]$Text, [string]$Pattern) {
    if ($null -eq $Text) { return $null }
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

function Get-ImageSize([string]$RelativePath) {
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try {
        $image = [System.Drawing.Image]::FromFile($path)
        try { return [pscustomobject]@{ Width = $image.Width; Height = $image.Height } } finally { $image.Dispose() }
    } catch {
        return $null
    }
}

function Add-KeyPaths($Node, [string]$Prefix, $List) {
    if ($null -eq $Node) { return }
    if ($Node -is [System.Management.Automation.PSCustomObject]) {
        foreach ($property in $Node.PSObject.Properties) {
            $child = $property.Name
            if ($Prefix) { $child = $Prefix + '.' + $property.Name }
            $List.Add($child)
            Add-KeyPaths $property.Value $child $List
        }
        return
    }
    if ($Node -is [System.Array]) {
        $index = 0
        foreach ($item in $Node) {
            $child = $Prefix + '[' + $index + ']'
            Add-KeyPaths $item $child $List
            $index++
        }
    }
}

function Add-JsonTypes($Node, $List) {
    if ($null -eq $Node) { return }
    if ($Node -is [System.Management.Automation.PSCustomObject]) {
        $typeProperty = $Node.PSObject.Properties['@type']
        if ($null -ne $typeProperty) {
            if ($typeProperty.Value -is [System.Array]) {
                foreach ($value in $typeProperty.Value) { $List.Add([string]$value) }
            } else {
                $List.Add([string]$typeProperty.Value)
            }
        }
        foreach ($property in $Node.PSObject.Properties) { Add-JsonTypes $property.Value $List }
        return
    }
    if ($Node -is [System.Array]) {
        foreach ($item in $Node) { Add-JsonTypes $item $List }
    }
}

function Get-JsonLdTypes([string]$HtmlText, [string]$Label) {
    $types = New-Object 'System.Collections.Generic.List[string]'
    $blocks = [regex]::Matches($HtmlText, '(?s)<script type="application/ld\+json">(.*?)</script>')
    if ($blocks.Count -eq 0) { return $types }
    foreach ($block in $blocks) {
        try {
            $node = $block.Groups[1].Value | ConvertFrom-Json -Depth 100
        } catch {
            Check ($Label + ': JSON-LD parses') $false $_.Exception.Message
            continue
        }
        Add-JsonTypes $node $types
    }
    return $types
}

function Get-VisibleText([string]$HtmlText) {
    $text = [regex]::Replace($HtmlText, '(?is)<script\b.*?</script>', ' ')
    $text = [regex]::Replace($text, '(?is)<style\b.*?</style>', ' ')
    $text = [regex]::Replace($text, '(?s)<[^>]+>', ' ')
    $text = [System.Net.WebUtility]::HtmlDecode($text)
    $text = [regex]::Replace($text, '\s+', ' ')
    return $text.Trim()
}

function Test-SiteTarget([string]$PageRelativePath, [string]$Href) {
    if ([string]::IsNullOrWhiteSpace($Href)) { return $true }
    if ($Href -match '^(https?://|mailto:|tel:|#|javascript:)') { return $true }
    $clean = $Href
    $hashIndex = $clean.IndexOf('#')
    if ($hashIndex -ge 0) { $clean = $clean.Substring(0, $hashIndex) }
    $queryIndex = $clean.IndexOf('?')
    if ($queryIndex -ge 0) { $clean = $clean.Substring(0, $queryIndex) }
    $clean = $clean.Trim()
    if ($clean -eq '') { return $true }
    $pageDirectory = Split-Path -Parent $PageRelativePath
    if ($pageDirectory -eq '') { $pageDirectory = '.' }
    if ($clean.StartsWith('/')) {
        $target = $clean.TrimStart('/')
    } else {
        $target = $pageDirectory + '/' + $clean
    }
    try {
        $full = [System.IO.Path]::GetFullPath((Join-Path $DistRoot $target))
    } catch {
        return $false
    }
    if (-not $full.StartsWith($DistRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
    if (Test-Path -LiteralPath $full -PathType Leaf) { return $true }
    if (Test-Path -LiteralPath $full -PathType Container) {
        return (Test-Path -LiteralPath (Join-Path $full 'index.html') -PathType Leaf)
    }
    return $false
}

function Get-BrokenLinks([string]$PageRelativePath, [string]$HtmlText) {
    $broken = New-Object 'System.Collections.Generic.List[string]'
    $links = [regex]::Matches($HtmlText, 'href="([^"]+)"')
    foreach ($link in $links) {
        $href = [System.Net.WebUtility]::HtmlDecode($link.Groups[1].Value)
        if (-not (Test-SiteTarget $PageRelativePath $href)) { $broken.Add($href) }
    }
    return $broken
}

function Get-BrokenImages([string]$PageRelativePath, [string]$HtmlText) {
    $broken = New-Object 'System.Collections.Generic.List[string]'
    $images = [regex]::Matches($HtmlText, '<img\b[^>]*>')
    foreach ($image in $images) {
        $tag = $image.Value
        $alt = Get-FirstGroup $tag 'alt="([^"]*)"'
        if ($null -eq $alt -or $alt.Trim() -eq '') { $broken.Add('missing-alt') }
        $src = Get-FirstGroup $tag 'src="([^"]+)"'
        if ($null -eq $src -or -not (Test-SiteTarget $PageRelativePath $src)) { $broken.Add('missing-src:' + [string]$src) }
    }
    return $broken
}

Write-Host 'Sirrak public-site validation'
Write-Host ('  repo root: ' + $RepoRoot)

# ---------------------------------------------------------------------------
Section 'Data sources and drift'
# ---------------------------------------------------------------------------
$site = $null
try { $site = (Read-RepoText 'website/site.json') | ConvertFrom-Json -Depth 100 } catch { }
Check 'website/site.json parses' ($null -ne $site)

$content = @{}
foreach ($code in @('en', 'ar')) {
    $node = $null
    try { $node = (Read-RepoText ('website/content/' + $code + '.json')) | ConvertFrom-Json -Depth 100 } catch { }
    Check ('website/content/' + $code + '.json parses') ($null -ne $node)
    if ($null -ne $node) { $content[$code] = $node }
}

$policies = $null
try { $policies = (Read-RepoText 'website/content/policies.json') | ConvertFrom-Json -Depth 100 } catch { }
Check 'website/content/policies.json parses' ($null -ne $policies)

$brand = $null
try { $brand = (Read-RepoText 'store/graphics/brand.json') | ConvertFrom-Json -Depth 100 } catch { }
Check 'store/graphics/brand.json parses' ($null -ne $brand)

$appGradle = Read-RepoText 'app/build.gradle.kts'
$appVersion = Get-FirstGroup $appGradle 'versionName\s*=\s*"([^"]+)"'
$appVersionCode = Get-FirstGroup $appGradle 'versionCode\s*=\s*(\d+)'

if ($null -ne $site) {
    Check 'site.json version matches app/build.gradle.kts' ($site.product.version -eq $appVersion) ('site ' + $site.product.version + ' vs gradle ' + $appVersion)
    Check 'site.json release date is an ISO date' ([string]$site.product.releaseDate -match '^\d{4}-\d{2}-\d{2}$')
    Check 'siteUrl is the project GitHub Pages URL' ($site.siteUrl -eq 'https://alshahia.github.io/sirrak/') ([string]$site.siteUrl)
    Check 'repository/issue/release URLs point at the same GitHub repository' (($site.repoUrl -eq 'https://github.com/alshahia/sirrak') -and ($site.issuesUrl -eq 'https://github.com/alshahia/sirrak/issues') -and ($site.releasesUrl -eq 'https://github.com/alshahia/sirrak/releases'))
    Check 'certificate SHA-256 is 64 uppercase hex characters' ([string]$site.certificateSha256 -match '^[0-9A-F]{64}$')
    Check 'site.json declares both languages (en, ar)' ((@($site.languages | ForEach-Object { [string]$_.code }) -join ',') -eq 'en,ar')
    Check 'every page file exists in site.json' ((@($site.pages.landing.file, $site.pages.privacy.file, $site.pages.security.file)).Count -eq 3)
}

if ($null -ne $policies -and $null -ne $site) {
    Check 'policy updated date equals the product release date' ([string]$policies.updated -eq [string]$site.product.releaseDate) ([string]$policies.updated)
}

if ($null -ne $content['en'] -and $null -ne $content['ar']) {
    $enKeys = New-Object 'System.Collections.Generic.List[string]'
    $arKeys = New-Object 'System.Collections.Generic.List[string]'
    Add-KeyPaths $content['en'] '' $enKeys
    Add-KeyPaths $content['ar'] '' $arKeys
    $contentDiff = @(Compare-Object -ReferenceObject $enKeys -DifferenceObject $arKeys)
    Check 'en and ar landing copy have identical structure' ($contentDiff.Count -eq 0) (($contentDiff | Select-Object -First 4 | ForEach-Object { [string]$_.SideIndicator + ' ' + [string]$_.InputObject }) -join '; ')
    $enHero = [string]$content['en'].hero.screenshot.src
    $arHero = [string]$content['ar'].hero.screenshot.src
    $heroOk = (-not [string]::IsNullOrWhiteSpace([string]$content['en'].hero.screenshot.alt)) -and
        (-not [string]::IsNullOrWhiteSpace([string]$content['ar'].hero.screenshot.alt)) -and
        ($enHero -match '^assets/screenshots/') -and ($arHero -match '^assets/screenshots/')
    Check 'each language declares a hero screenshot with alt text' $heroOk ($enHero + ' | ' + $arHero)
}

if ($null -ne $policies) {
    $policyEnKeys = New-Object 'System.Collections.Generic.List[string]'
    $policyArKeys = New-Object 'System.Collections.Generic.List[string]'
    Add-KeyPaths $policies.privacy.en '' $policyEnKeys
    Add-KeyPaths $policies.privacy.ar '' $policyArKeys
    Check 'privacy policy copy has the same structure in both languages' ((@(Compare-Object -ReferenceObject $policyEnKeys -DifferenceObject $policyArKeys)).Count -eq 0)
    $securityEnKeys = New-Object 'System.Collections.Generic.List[string]'
    $securityArKeys = New-Object 'System.Collections.Generic.List[string]'
    Add-KeyPaths $policies.security.en '' $securityEnKeys
    Add-KeyPaths $policies.security.ar '' $securityArKeys
    Check 'security policy copy has the same structure in both languages' ((@(Compare-Object -ReferenceObject $securityEnKeys -DifferenceObject $securityArKeys)).Count -eq 0)
    Check 'privacy policy has at least six sections' ((@($policies.privacy.en.sections)).Count -ge 6)
    Check 'security policy has at least four sections' ((@($policies.security.en.sections)).Count -ge 4)
}

$appConfig = $null
try { $appConfig = (Read-RepoText 'app/src/main/assets/config/app.json') | ConvertFrom-Json -Depth 100 } catch { }
if ($null -ne $appConfig -and $null -ne $site) {
    Check 'app config links the hosted privacy policy' ([string]$appConfig.privacy.policyUrl -eq ($site.siteUrl + 'privacy/')) ([string]$appConfig.privacy.policyUrl)
    Check 'app config links the hosted security policy' ([string]$appConfig.privacy.securityUrl -eq ($site.siteUrl + 'security/')) ([string]$appConfig.privacy.securityUrl)
    Check 'app config points issues at the project issue tracker' ([string]$appConfig.privacy.reportIssueUrl -eq ($site.issuesUrl + '/new/choose')) ([string]$appConfig.privacy.reportIssueUrl)
}

# ---------------------------------------------------------------------------
Section 'Generated output'
# ---------------------------------------------------------------------------
$expectedFiles = @(
    'website/dist/index.html',
    'website/dist/ar/index.html',
    'website/dist/privacy/index.html',
    'website/dist/ar/privacy/index.html',
    'website/dist/security/index.html',
    'website/dist/ar/security/index.html',
    'website/dist/404.html',
    'website/dist/sitemap.xml',
    'website/dist/robots.txt',
    'website/dist/manifest.webmanifest',
    'website/dist/llms.txt',
    'website/dist/.nojekyll',
    'website/dist/assets/site.css'
)
foreach ($relative in $expectedFiles) {
    Check ('generated ' + $relative.Replace('website/dist/', '')) (Test-RepoFile $relative)
}
$staleFiles = @(Get-ChildItem -LiteralPath $DistRoot -File -ErrorAction SilentlyContinue | Where-Object { $_.Name.StartsWith('__') -or $_.Name.StartsWith('._') })
Check 'no temporary or OS metadata files left in website/dist' ($staleFiles.Count -eq 0) (($staleFiles | ForEach-Object { $_.Name }) -join ', ')

# ---------------------------------------------------------------------------
Section 'Pages: head, SEO, links, accessibility'
# ---------------------------------------------------------------------------
$pageDefinitions = @(
    [pscustomobject]@{ Key = 'landing'; Suffix = ''; File = 'index.html'; JsonLd = @('SoftwareApplication', 'WebSite', 'FAQPage') },
    [pscustomobject]@{ Key = 'privacy'; Suffix = 'privacy/'; File = 'privacy/index.html'; JsonLd = @('PrivacyPolicy') },
    [pscustomobject]@{ Key = 'security'; Suffix = 'security/'; File = 'security/index.html'; JsonLd = @('WebPage') }
)

foreach ($language in @($site.languages)) {
    $code = [string]$language.code
    $htmlLang = [string]$language.htmlLang
    $htmlDir = [string]$language.dir
    $langPath = [string]$language.path
    foreach ($definition in $pageDefinitions) {
        $relative = $langPath + $definition.File
        $label = $code + ' ' + $definition.Key
        $html = Read-RepoText ('website/dist/' + $relative)
        Check ($label + ': page exists') ($null -ne $html) $relative
        if ($null -eq $html) { continue }
        $canonical = $site.siteUrl + $langPath + $definition.Suffix
        $visible = Get-VisibleText $html

        $doctypeOk = $html.TrimStart().ToLowerInvariant().StartsWith('<!doctype html>')
        $charsetOk = $html -match '<meta charset="utf-8">'
        $viewportOk = $html -match '<meta name="viewport" content="width=device-width, initial-scale=1">'
        Check ($label + ': doctype, charset and viewport') ($doctypeOk -and $charsetOk -and $viewportOk)

        $htmlElementOk = ($html -match ('<html lang="' + $htmlLang + '" dir="' + $htmlDir + '">'))
        Check ($label + ': html lang and dir are correct') $htmlElementOk

        $title = Get-FirstGroup $html '(?s)<title>(.*?)</title>'
        $titleOk = ($null -ne $title) -and ($title.Length -ge 10) -and ($title.Length -le 90)
        Check ($label + ': title is present and 10-90 characters') $titleOk ([string]$title)
        $description = Get-FirstGroup $html '<meta name="description" content="([^"]*)">'
        $descriptionOk = ($null -ne $description) -and ($description.Length -ge 60) -and ($description.Length -le 200)
        Check ($label + ': meta description is present and 60-200 characters') $descriptionOk ([string]$description.Length)
        $robots = Get-FirstGroup $html '<meta name="robots" content="([^"]*)">'
        Check ($label + ': robots meta allows indexing') ($null -ne $robots -and $robots.Contains('index'))

        $canonicalHref = Get-FirstGroup $html '<link rel="canonical" href="([^"]+)">'
        Check ($label + ': canonical URL is correct') ($canonicalHref -eq $canonical) ([string]$canonicalHref)

        $alternates = New-Object 'System.Collections.Generic.List[string]'
        $alternateMatches = [regex]::Matches($html, '<link rel="alternate" hreflang="([^"]+)" href="([^"]+)">')
        foreach ($alternate in $alternateMatches) { $alternates.Add($alternate.Groups[1].Value + '=' + $alternate.Groups[2].Value) }
        $alternateOk = $true
        foreach ($alternateLanguage in @($site.languages)) {
            $expectedHref = $site.siteUrl + [string]$alternateLanguage.path + $definition.Suffix
            $found = $alternates | Where-Object { $_ -eq ([string]$alternateLanguage.code + '=' + $expectedHref) }
            if ($null -eq $found) { $alternateOk = $false }
        }
        if (-not ($alternates -contains ('x-default=' + $site.siteUrl + $definition.Suffix))) { $alternateOk = $false }
        Check ($label + ': hreflang alternates for en, ar and x-default') $alternateOk (($alternates.ToArray()) -join ', ')

        $ogImage = Get-FirstGroup $html '<meta property="og:image" content="([^"]+)">'
        $ogFieldsOk = ($null -ne (Get-FirstGroup $html '<meta property="og:title" content="([^"]+)">')) -and
            ($null -ne (Get-FirstGroup $html '<meta property="og:description" content="([^"]+)">')) -and
            ($null -ne $ogImage) -and $ogImage.StartsWith($site.siteUrl) -and
            ((Get-FirstGroup $html '<meta property="og:image:width" content="([^"]+)">') -eq '1200') -and
            ((Get-FirstGroup $html '<meta property="og:image:height" content="([^"]+)">') -eq '630') -and
            ((Get-FirstGroup $html '<meta property="og:url" content="([^"]+)">') -eq $canonical) -and
            ($null -ne (Get-FirstGroup $html '<meta property="og:locale" content="([^"]+)">')) -and
            ($null -ne (Get-FirstGroup $html '<meta property="og:locale:alternate" content="([^"]+)">')) -and
            ($null -ne (Get-FirstGroup $html '<meta property="og:image:alt" content="([^"]+)">'))
        Check ($label + ': Open Graph block is complete') $ogFieldsOk
        $ogImageRelative = 'website/dist/' + $ogImage.Replace($site.siteUrl, '')
        Check ($label + ': Open Graph image file exists') (Test-RepoFile $ogImageRelative) $ogImageRelative

        $twitterOk = ((Get-FirstGroup $html '<meta name="twitter:card" content="([^"]+)">') -eq 'summary_large_image') -and
            ($null -ne (Get-FirstGroup $html '<meta name="twitter:title" content="([^"]+)">')) -and
            ($null -ne (Get-FirstGroup $html '<meta name="twitter:description" content="([^"]+)">')) -and
            ($null -ne (Get-FirstGroup $html '<meta name="twitter:image" content="([^"]+)">'))
        Check ($label + ': Twitter card block is complete') $twitterOk

        $types = Get-JsonLdTypes $html $label
        $typesOk = @($definition.JsonLd | Where-Object { @($types) -notcontains $_ }).Count -eq 0
        Check ($label + ': JSON-LD has the expected schema types') $typesOk (@($types) -join ', ')

        $iconsOk = ($null -ne (Get-FirstGroup $html '<link rel="icon" [^>]*href="([^"]+)"')) -and
            ($null -ne (Get-FirstGroup $html '<link rel="apple-touch-icon" href="([^"]+)">')) -and
            ($null -ne (Get-FirstGroup $html '<link rel="manifest" href="([^"]+)">')) -and
            ($null -ne (Get-FirstGroup $html '<link rel="stylesheet" href="([^"]+)">'))
        Check ($label + ': icon, manifest and stylesheet links are declared') $iconsOk

        $noExternalScripts = ($html -notmatch '<script[^>]+src=') -and ($html -notmatch '<iframe') -and
            ($html -notmatch 'googletagmanager') -and ($html -notmatch 'google-analytics') -and
            ($html -notmatch 'plausible') -and ($html -notmatch 'umami') -and ($html -notmatch 'localhost') -and
            ($html -notmatch '127\.0\.0\.1')
        Check ($label + ': no third-party scripts, iframes or analytics') $noExternalScripts

        $brokenLinks = @(Get-BrokenLinks $relative $html)
        Check ($label + ': every internal link resolves to a generated file') ($brokenLinks.Count -eq 0) (($brokenLinks | Select-Object -First 4) -join ', ')

        $brokenImages = @(Get-BrokenImages $relative $html)
        Check ($label + ': every image has alt text and a resolvable source') ($brokenImages.Count -eq 0) (($brokenImages | Select-Object -First 4) -join ', ')

        $localPathsOk = ($html -notmatch 'D:/sirrak') -and ($html -notmatch 'D:\\sirrak') -and ($html -notmatch 'E:/rea') -and
            ($html -notmatch 'file:///') -and ($html -notmatch 'C:/Users')
        Check ($label + ': no local absolute paths in the generated page') $localPathsOk

        $noPlaceholders = ($html -notmatch '(?i)\bTODO\b') -and ($html -notmatch '(?i)\bFIXME\b') -and ($html -notmatch 'lorem ipsum')
        Check ($label + ': no TODO/FIXME/placeholder text') $noPlaceholders

        $visibleOk = $visible.Length -ge 900
        Check ($label + ': meaningful content is present without JavaScript') $visibleOk ([string]$visible.Length + ' chars')
        if ($code -eq 'ar') {
            $arabicCount = ([regex]::Matches($visible, '[\u0600-\u06FF]')).Count
            Check ($label + ': page body is genuinely Arabic') ($arabicCount -ge 250) ([string]$arabicCount + ' Arabic characters')
        }

        Check ($label + ': exactly one h1 heading') (([regex]::Matches($html, '<h1[ >]')).Count -eq 1)
        if ($definition.Key -eq 'landing') {
            $sectionIdsOk = $true
            foreach ($sectionId in @('steps', 'inventory', 'evidence', 'privacy', 'download', 'status', 'faq', 'docs')) {
                if ($html -notmatch ('<section[^>]*id="' + $sectionId + '"')) { $sectionIdsOk = $false }
            }
            Check ($label + ': every landing section is rendered') $sectionIdsOk
            $faqCount = @($content[$code].faq.items).Count
            Check ($label + ': FAQ renders every question') (([regex]::Matches($html, '<details')).Count -ge $faqCount) ([string]$faqCount + ' questions')
            $hero = $content[$code].hero.screenshot
            $heroPath = 'website/dist/' + [string]$hero.src
            Check ($label + ': hero screenshot exists') (Test-RepoFile $heroPath) $heroPath
            $legendCount = ([regex]::Matches($html, 'class="callout-legend-num"')).Count
            Check ($label + ': hero annotation legend matches the callouts') ($legendCount -eq @($hero.callouts).Count)
            Check ($label + ': release certificate fingerprint is shown for verification') ($html.Contains([string]$site.certificateSha256))
            Check ($label + ': links to the hosted policies') ($html -match ('href="[^"]*' + [regex]::Escape('privacy/') + '"'))
            $skipLinkOk = ($html -match 'class="skip-link"') -or ($html -match 'href="#main"')
            Check ($label + ': a skip link points at the main landmark') $skipLinkOk
        } else {
            $policy = $policies.($definition.Key).($code)
            $sectionCount = @($policy.sections).Count
            Check ($label + ': every policy section is rendered') (([regex]::Matches($html, 'class="policy-section"')).Count -eq $sectionCount) ([string]$sectionCount)
            Check ($label + ': policy dateline shows the update year') ($html -match '2026')
            Check ($label + ': policy page links back to the landing page') ($html -match 'class="policy-back"')
            Check ($label + ': policy summary has three key points') (([regex]::Matches($html, '<li>')).Count -ge 3)
        }
    }
}

# ---------------------------------------------------------------------------
Section 'Shared assets, brand and discovery files'
# ---------------------------------------------------------------------------
$css = Read-RepoText 'website/dist/assets/site.css'
Check 'site.css is generated and substantial' ($null -ne $css -and $css.Length -gt 15000) ([string]$css.Length + ' bytes')
Check 'site.css declares the four brand font faces' (([regex]::Matches($css, '@font-face')).Count -eq 4)
if ($null -ne $brand) {
    $paletteOk = $true
    $paletteDetail = ''
    foreach ($property in $brand.palette.PSObject.Properties) {
        if ($css -notmatch [regex]::Escape([string]$property.Value)) { $paletteOk = $false; $paletteDetail = $paletteDetail + $property.Name + ' ' }
    }
    Check 'brand palette is the single source of the CSS tokens' $paletteOk $paletteDetail
}

$fontAssets = @(
    'website/dist/assets/fonts/amiri-bold.ttf',
    'website/dist/assets/fonts/amiri-regular.ttf',
    'website/dist/assets/fonts/plex-arabic-regular.ttf',
    'website/dist/assets/fonts/plex-arabic-semibold.ttf'
)
foreach ($fontAsset in $fontAssets) {
    $fontFile = Get-Item -LiteralPath (Join-Path $RepoRoot $fontAsset) -ErrorAction SilentlyContinue
    Check ('font asset exists: ' + [System.IO.Path]::GetFileName($fontAsset)) ($null -ne $fontFile -and $fontFile.Length -gt 100000)
}

$ogSize = Get-ImageSize 'website/dist/assets/og-image.png'
Check 'Open Graph image is 1200x630' ($null -ne $ogSize -and $ogSize.Width -eq 1200 -and $ogSize.Height -eq 630) ([string]$ogSize.Width + 'x' + [string]$ogSize.Height)

$iconSpecs = @(
    @{ Path = 'website/dist/assets/icon-512.png'; Size = 512 },
    @{ Path = 'website/dist/assets/icon-192.png'; Size = 192 },
    @{ Path = 'website/dist/assets/apple-touch-icon.png'; Size = 180 },
    @{ Path = 'website/dist/assets/favicon-32.png'; Size = 32 }
)
foreach ($iconSpec in $iconSpecs) {
    $iconSize = Get-ImageSize $iconSpec.Path
    Check ('icon has the right dimensions: ' + [System.IO.Path]::GetFileName($iconSpec.Path)) ($null -ne $iconSize -and $iconSize.Width -eq $iconSpec.Size -and $iconSize.Height -eq $iconSpec.Size)
}

if ($null -ne $site) {
    foreach ($shot in $site.assets.screenshots.PSObject.Properties) {
        $shotPath = 'website/dist/' + [string]$shot.Value
        $shotSize = Get-ImageSize $shotPath
        Check ('screenshot is 720x1280: ' + [string]$shot.Value) ($null -ne $shotSize -and $shotSize.Width -eq 720 -and $shotSize.Height -eq 1280)
    }
    $heroImage = Get-ImageSize ('website/dist/assets/' + [string]$content['en'].hero.screenshot.src.Replace('assets/', ''))
    Check 'hero screenshot is a 720x1280 app capture' ($null -ne $heroImage -and $heroImage.Width -eq 720)
}

if ($null -ne $brand) {
    $markOk = $true
    foreach ($markPath in @($brand.mark.paths)) {
        $enLanding = Read-RepoText 'website/dist/index.html'
        if ($enLanding -notmatch [regex]::Escape([string]$markPath.d)) { $markOk = $false }
    }
    Check 'brand mark is rendered from brand.json paths' $markOk
}

$manifest = $null
try { $manifest = (Read-RepoText 'website/dist/manifest.webmanifest') | ConvertFrom-Json -Depth 50 } catch { }
Check 'web manifest parses' ($null -ne $manifest)
if ($null -ne $manifest) {
    Check 'web manifest has name, start_url and standalone display' (($null -ne $manifest.name) -and ($null -ne $manifest.start_url) -and ($manifest.display -eq 'standalone'))
    $manifestIcons = @($manifest.icons)
    $has192 = @($manifestIcons | Where-Object { [string]$_.sizes -eq '192x192' }).Count -gt 0
    $has512 = @($manifestIcons | Where-Object { [string]$_.sizes -eq '512x512' }).Count -gt 0
    Check 'web manifest declares 192 and 512 icons' ($has192 -and $has512)
    Check 'web manifest theme colour follows the brand ground token' ($manifest.theme_color -eq [string]$brand.palette.ground)
}

$robots = Read-RepoText 'website/dist/robots.txt'
Check 'robots.txt allows crawling' ($null -ne $robots -and $robots -match 'User-agent: \*' -and $robots -match 'Allow: /' -and $robots -notmatch 'Disallow: /$')
Check 'robots.txt publishes the sitemap URL' ($null -ne $robots -and $robots.Contains([string]$site.siteUrl + 'sitemap.xml'))

$sitemap = Read-RepoText 'website/dist/sitemap.xml'
Check 'sitemap.xml parses as XML' ($null -ne $sitemap -and $sitemap.TrimStart().StartsWith('<?xml'))
if ($null -ne $sitemap) {
    $locations = [regex]::Matches($sitemap, '<loc>([^<]+)</loc>')
    $expectedLocations = @()
    foreach ($language in @($site.languages)) {
        $expectedLocations += ($site.siteUrl + [string]$language.path)
        $expectedLocations += ($site.siteUrl + [string]$language.path + 'privacy/')
        $expectedLocations += ($site.siteUrl + [string]$language.path + 'security/')
    }
    $locationsOk = ($locations.Count -eq $expectedLocations.Count)
    foreach ($location in $locations) {
        if ($expectedLocations -notcontains $location.Groups[1].Value) { $locationsOk = $false }
    }
    Check 'sitemap lists every public page exactly once' $locationsOk ([string]$locations.Count + ' locations')
    Check 'sitemap declares hreflang alternates' (([regex]::Matches($sitemap, 'hreflang="x-default"')).Count -eq $locations.Count)
    Check 'sitemap carries the release date as lastmod' (([regex]::Matches($sitemap, [regex]::Escape([string]$site.product.releaseDate))).Count -eq $locations.Count)
}

$llms = Read-RepoText 'website/dist/llms.txt'
Check 'llms.txt is present, structured and links the site' ($null -ne $llms -and $llms.Length -gt 400 -and $llms.Contains([string]$site.siteUrl) -and (([regex]::Matches($llms, '\n')).Count -ge 10))

$notFound = Read-RepoText 'website/dist/404.html'
Check '404 page exists with noindex' ($null -ne $notFound -and $notFound -match '<meta name="robots" content="noindex"')
if ($null -ne $notFound) {
    Check '404 page offers both languages' (($notFound -match 'href="[^"]*ar/') -or ($notFound -match 'href="[^"]*sirrak/'))
}

$distHtml = @(Get-ChildItem -LiteralPath $DistRoot -Recurse -File -Filter '*.html')
Check 'generated HTML set is exactly the six pages plus 404' ($distHtml.Count -eq 7) ([string]$distHtml.Count + ' files')
$distBytes = (Get-ChildItem -LiteralPath $DistRoot -Recurse -File | Measure-Object -Property Length -Sum).Sum
Check 'generated site is self-contained and complete' ($distBytes -gt 2000000) ([Math]::Round($distBytes / 1MB, 2).ToString() + ' MiB')

$allInsecure = @()
foreach ($htmlFile in $distHtml) {
    $text = [System.IO.File]::ReadAllText($htmlFile.FullName)
    if ($text -match 'http://(?!localhost)') { $allInsecure += $htmlFile.Name }
}
Check 'no insecure http:// links in generated pages' ($allInsecure.Count -eq 0) ($allInsecure -join ', ')

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
if (-not $Quiet) { Write-Host '' }
Write-Host ('site validation: ' + $script:checks + ' checks, ' + $script:failures.Count + ' failed')
if ($script:failures.Count -gt 0) {
    Write-Host 'failed checks:'
    foreach ($failure in $script:failures) { Write-Host ('  - ' + $failure) -ForegroundColor Red }
    exit 1
}
Write-Host 'all site checks passed.' -ForegroundColor Green
exit 0
