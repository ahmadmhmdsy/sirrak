#requires -Version 7.0
<#
  Sirrak website generator.
  Reads website/site.json + website/content/{en,ar}.json + website/content/policies.json
  + store/graphics/brand.json, and writes the committed static site to website/dist/.
  No framework, no network, no third-party scripts: portable across Windows and CI.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$nl = [System.Environment]::NewLine

$repoRoot = Split-Path -Parent $PSScriptRoot
$siteRoot = Join-Path $repoRoot 'website'
$distRoot = Join-Path $siteRoot 'dist'
$themePath = Join-Path $siteRoot 'theme/site.css'
$buildDir = Join-Path $siteRoot '.build'

function Read-JsonFile([string]$relPath) {
    $full = Join-Path $repoRoot $relPath
    if (-not (Test-Path -LiteralPath $full)) { throw "missing input: $full" }
    return (Get-Content -LiteralPath $full -Raw -Encoding utf8 | ConvertFrom-Json)
}

function Esc-Html([string]$text) {
    if ($null -eq $text) { return '' }
    $out = $text -replace '&', '&amp;'
    $out = $out -replace '<', '&lt;'
    $out = $out -replace '>', '&gt;'
    $out = $out -replace '"', '&quot;'
    $out = $out -replace "'", '&#39;'
    return $out
}

function Esc-Url([string]$text) {
    if ($null -eq $text) { return '' }
    return ($text -replace '&', '&amp;' -replace '"', '&quot;')
}

function New-Prefix([int]$depth) {
    $prefix = ''
    for ($i = 0; $i -lt $depth; $i++) { $prefix += '../' }
    return $prefix
}

function Get-Opt($obj, [string]$name) {
    if ($null -eq $obj) { return $null }
    $prop = $obj.PSObject.Properties[$name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Save-Text([string]$relativePath, [string]$content) {
    $full = Join-Path $distRoot $relativePath
    $dir = Split-Path -Parent $full
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($full, $content, [System.Text.UTF8Encoding]::new($false))
}

function Copy-Asset([string]$sourceRel, [string]$targetRel) {
    $source = Join-Path $repoRoot $sourceRel
    if (-not (Test-Path -LiteralPath $source)) { throw "missing asset source: $source" }
    $target = Join-Path $distRoot $targetRel
    $dir = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Copy-Item -LiteralPath $source -Destination $target -Force
}

# ---------------------------------------------------------------------------
# Inputs
# ---------------------------------------------------------------------------
$site = Read-JsonFile 'website/site.json'
$brand = Read-JsonFile 'store/graphics/brand.json'
$policies = Read-JsonFile 'website/content/policies.json'

if (Test-Path -LiteralPath $distRoot) { Remove-Item -LiteralPath $distRoot -Recurse -Force }
New-Item -ItemType Directory -Path $distRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $buildDir)) { New-Item -ItemType Directory -Path $buildDir -Force | Out-Null }

$langs = @()
foreach ($langEntry in @($site.languages)) {
    $pathValue = [string]$langEntry.path
    $segments = @($pathValue.Split('/') | Where-Object { $_ -ne '' })
    $contentPath = 'website/content/' + $langEntry.code + '.json'
    $langs += @{
        code = [string]$langEntry.code
        path = $pathValue
        htmlLang = [string]$langEntry.htmlLang
        dir = [string]$langEntry.dir
        label = [string]$langEntry.label
        switchLabel = [string]$langEntry.switchLabel
        ogLocale = [string]$langEntry.ogLocale
        alternateOgLocale = [string]$langEntry.alternateOgLocale
        depth = $segments.Count
        content = (Read-JsonFile $contentPath)
    }
}

$pages = @()
foreach ($lang in $langs) {
    $defs = @(
        @{ key = 'landing'; suffix = ''; file = ($lang.path + 'index.html') },
        @{ key = 'privacy'; suffix = 'privacy/'; file = ($lang.path + 'privacy/index.html') },
        @{ key = 'security'; suffix = 'security/'; file = ($lang.path + 'security/index.html') }
    )
    foreach ($def in $defs) {
        $depth = $def.file.Split('/').Count - 1
        $sitePrefix = New-Prefix $depth
        $langPrefix = New-Prefix ($depth - $lang.depth)
        $brandHref = $sitePrefix + $lang.path
        if ($brandHref -eq '') { $brandHref = './' }
        $pages += @{
            lang = $lang
            key = $def.key
            file = $def.file
            suffix = $def.suffix
            depth = $depth
            sitePrefix = $sitePrefix
            langPrefix = $langPrefix
            brandHref = $brandHref
            canonical = ($site.siteUrl + $lang.path + $def.suffix)
        }
    }
}

function Resolve-Link([string]$href, $page) {
    if ([string]::IsNullOrWhiteSpace($href)) { return '#' }
    if ($href -match '^(https?:)?//') { return $href }
    if ($href.StartsWith('mailto:')) { return $href }
    if ($href.StartsWith('#')) {
        if ($page.key -eq 'landing') { return $href }
        return $page.langPrefix + $href
    }
    if ($href.StartsWith('/')) { return $page.sitePrefix + $href.TrimStart('/') }
    return $page.langPrefix + $href
}

function Get-BrandSvg([string]$className) {
    $mark = $brand.mark
    $paths = ''
    foreach ($p in @($mark.paths)) {
        $paths += ('<path d="{0}" />' -f (Esc-Html ([string]$p.d)))
    }
    $viewBox = '0 0 {0} {0}' -f [int]$mark.viewport
    return ('<svg class="{0}" viewBox="{1}" fill="none" stroke="currentColor" stroke-width="4.5" stroke-linejoin="round" stroke-linecap="round" aria-hidden="true" focusable="false">{2}</svg>' -f $className, $viewBox, $paths)
}

function Get-Icon([string]$name) {
    switch ($name) {
        'chat' { return '<svg class="inv-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4.5 5.5h15v9.5h-8.6L6 19v-4H4.5z"/><path d="M8 9.5h8M8 12h5"/></svg>' }
        'mic' { return '<svg class="inv-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="3" width="6" height="10" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M9 21h6"/></svg>' }
        'document' { return '<svg class="inv-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6.5 3h7l4.5 4.5V21h-11.5z"/><path d="M13.5 3v4.5H18M9.5 12h5M9.5 15.5h5"/></svg>' }
        'models' { return '<svg class="inv-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3.5 20.5 8 12 12.5 3.5 8z"/><path d="M3.5 12.5 12 17l8.5-4.5M3.5 16.5 12 21l8.5-4.5"/></svg>' }
        'sliders' { return '<svg class="inv-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h7M15 7h5M4 12h3M11 12h9M4 17h9M17 17h3"/><circle cx="13" cy="7" r="2"/><circle cx="9" cy="12" r="2"/><circle cx="15" cy="17" r="2"/></svg>' }
        'shield' { return '<svg class="inv-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3 19 6v5.2c0 4.4-2.9 7.4-7 9.3-4.1-1.9-7-4.9-7-9.3V6z"/><path d="m8.6 11.6 2.4 2.4 4.4-4.4"/></svg>' }
        default { return '' }
    }
}

# ---------------------------------------------------------------------------
# Shared page chrome
# ---------------------------------------------------------------------------
function New-HeadBlock($page, [string]$title, [string]$description, [string]$jsonLd) {
    $lang = $page.lang
    $c = $lang.content
    $prefix = $page.sitePrefix
    $assetIcon = Esc-Url ($prefix + 'assets/icon-192.png')
    $assetApple = Esc-Url ($prefix + 'assets/apple-touch-icon.png')
    $assetFavicon = Esc-Url ($prefix + 'assets/favicon-32.png')
    $assetCss = Esc-Url ($prefix + 'assets/site.css')
    $manifest = Esc-Url ($prefix + 'manifest.webmanifest')
    $fontDisplay = Esc-Url ($prefix + 'assets/fonts/amiri-bold.ttf')
    $fontBody = Esc-Url ($prefix + 'assets/fonts/plex-arabic-regular.ttf')
    $ogImage = $site.siteUrl + 'assets/og-image.png'

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('<!doctype html>')
    $lines.Add(('<html lang="{0}" dir="{1}">' -f (Esc-Html $lang.htmlLang), (Esc-Html $lang.dir)))
    $lines.Add('<head>')
    $lines.Add('<meta charset="utf-8">')
    $lines.Add('<meta name="viewport" content="width=device-width, initial-scale=1">')
    $lines.Add(('<title>{0}</title>' -f (Esc-Html $title)))
    $lines.Add(('<meta name="description" content="{0}">' -f (Esc-Html $description)))
    $lines.Add(('<meta name="author" content="Sirrak contributors">'))
    $lines.Add(('<meta name="theme-color" content="{0}">' -f $brand.palette.ground))
    $lines.Add('<meta name="color-scheme" content="light">')
    $lines.Add('<meta name="robots" content="index, follow, max-image-preview:large">')
    $lines.Add(('<link rel="canonical" href="{0}">' -f (Esc-Url $page.canonical)))
    foreach ($otherLang in $langs) {
        $altHref = $site.siteUrl + $otherLang.path + $page.suffix
        $lines.Add(('<link rel="alternate" hreflang="{0}" href="{1}">' -f $otherLang.htmlLang, (Esc-Url $altHref)))
    }
    $defaultHref = $site.siteUrl + $page.suffix
    $lines.Add(('<link rel="alternate" hreflang="x-default" href="{0}">' -f (Esc-Url $defaultHref)))
    $lines.Add(('<link rel="icon" href="{0}" sizes="32x32" type="image/png">' -f $assetFavicon))
    $lines.Add(('<link rel="icon" href="{0}" sizes="192x192" type="image/png">' -f $assetIcon))
    $lines.Add(('<link rel="apple-touch-icon" href="{0}">' -f $assetApple))
    $lines.Add(('<link rel="manifest" href="{0}">' -f $manifest))
    $lines.Add(('<link rel="preload" href="{0}" as="font" type="font/ttf" crossorigin>' -f $fontDisplay))
    $lines.Add(('<link rel="preload" href="{0}" as="font" type="font/ttf" crossorigin>' -f $fontBody))
    $lines.Add(('<link rel="stylesheet" href="{0}">' -f $assetCss))
    $lines.Add(('<meta property="og:type" content="website">'))
    $lines.Add(('<meta property="og:site_name" content="Sirrak · سِرِّك">'))
    $lines.Add(('<meta property="og:title" content="{0}">' -f (Esc-Html $title)))
    $lines.Add(('<meta property="og:description" content="{0}">' -f (Esc-Html $description)))
    $lines.Add(('<meta property="og:url" content="{0}">' -f (Esc-Url $page.canonical)))
    $lines.Add(('<meta property="og:image" content="{0}">' -f (Esc-Url $ogImage)))
    $lines.Add('<meta property="og:image:type" content="image/png">')
    $lines.Add('<meta property="og:image:width" content="1200">')
    $lines.Add('<meta property="og:image:height" content="630">')
    $lines.Add(('<meta property="og:image:alt" content="{0}">' -f (Esc-Html 'سِرِّك — مساعدك الخاص يعمل على جهازك')))
    $lines.Add(('<meta property="og:locale" content="{0}">' -f $lang.ogLocale))
    $lines.Add(('<meta property="og:locale:alternate" content="{0}">' -f $lang.alternateOgLocale))
    $lines.Add('<meta name="twitter:card" content="summary_large_image">')
    $lines.Add(('<meta name="twitter:title" content="{0}">' -f (Esc-Html $title)))
    $lines.Add(('<meta name="twitter:description" content="{0}">' -f (Esc-Html $description)))
    $lines.Add(('<meta name="twitter:image" content="{0}">' -f (Esc-Url $ogImage)))
    $lines.Add(('<script type="application/ld+json">' + $jsonLd + '</script>'))
    $lines.Add('</head>')
    return ($lines -join $nl)
}

function New-SkipAndHeader($page) {
    $lang = $page.lang
    $c = $lang.content
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add(('<a class="skip" href="#main">{0}</a>' -f (Esc-Html $c.nav.skip)))
    $lines.Add('<header class="site-header">')
    $lines.Add('<div class="wrap header-inner">')
    $lines.Add(('<a class="brand" href="{0}" aria-label="Sirrak — سِرِّك">{1}<span class="brand-name" lang="ar">{2}</span><span class="brand-latin" aria-hidden="true">{3}</span></a>' -f (Esc-Url $page.brandHref), (Get-BrandSvg 'brand-mark'), (Esc-Html $brand.copy.arabicWordmark), (Esc-Html $brand.copy.latinWordmark)))
    $lines.Add(('<nav class="site-nav" aria-label="{0}"><ul>' -f (Esc-Html $c.nav.brand)))
    foreach ($link in @($c.nav.links)) {
        $lines.Add(('<li><a href="{0}">{1}</a></li>' -f (Esc-Url (Resolve-Link ([string]$link.href) $page)), (Esc-Html ([string]$link.label))))
    }
    $lines.Add('</ul></nav>')
    $otherLang = $null
    foreach ($candidate in $langs) { if ($candidate.code -ne $lang.code) { $otherLang = $candidate } }
    $switchHref = $page.sitePrefix + $otherLang.path + $page.suffix
    $lines.Add(('<a class="lang-switch" href="{0}" hreflang="{1}" lang="{1}">{2}</a>' -f (Esc-Url $switchHref), (Esc-Html $otherLang.htmlLang), (Esc-Html ([string]$c.nav.switch.label))))
    $lines.Add('</div>')
    $lines.Add('</header>')
    return ($lines -join $nl)
}

function New-Footer($page) {
    $lang = $page.lang
    $c = $lang.content
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('<footer class="site-footer">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<div class="footer-top">')
    $lines.Add('<div>')
    $lines.Add(('<p class="footer-mark" lang="ar">{0}</p>' -f (Esc-Html $brand.copy.arabicWordmark)))
    $lines.Add(('<p class="footer-tagline">{0}</p>' -f (Esc-Html ([string]$c.footer.tagline))))
    $lines.Add('</div>')
    $lines.Add('<div class="footer-columns">')
    foreach ($column in @($c.footer.columns)) {
        $lines.Add('<div>')
        $lines.Add(('<h3>{0}</h3>' -f (Esc-Html ([string]$column.title))))
        $lines.Add('<ul>')
        foreach ($link in @($column.links)) {
            $lines.Add(('<li><a href="{0}">{1}</a></li>' -f (Esc-Url (Resolve-Link ([string]$link.href) $page)), (Esc-Html ([string]$link.label))))
        }
        $lines.Add('</ul>')
        $lines.Add('</div>')
    }
    $lines.Add('</div>')
    $lines.Add('</div>')
    $lines.Add('<div class="footer-legal">')
    $lines.Add(('<span>{0}</span>' -f (Esc-Html ([string]$c.footer.legal))))
    $lines.Add(('<span>© 2026 Sirrak contributors · <a href="{0}">Apache-2.0</a></span>' -f 'https://github.com/alshahia/sirrak/blob/main/LICENSE'))
    $lines.Add('</div>')
    $lines.Add('</div>')
    $lines.Add('</footer>')
    return ($lines -join $nl)
}

function New-ScriptBlock {
    return @'
<script>
(function () {
  var steps = document.querySelectorAll('.step');
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!steps.length || reduce || !('IntersectionObserver' in window)) { return; }
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) { entry.target.classList.remove('is-past'); return; }
      if (entry.boundingClientRect.bottom < (entry.rootBounds ? entry.rootBounds.top : 0)) {
        entry.target.classList.add('is-past');
      }
    });
  }, { threshold: 0, rootMargin: '-10% 0px 0px 0px' });
  steps.forEach(function (step) { io.observe(step); });
})();
</script>
'@
}

# ---------------------------------------------------------------------------
# JSON-LD
# ---------------------------------------------------------------------------
function New-OrganizationJson {
    return [ordered]@{
        '@type' = 'Organization'
        name = 'Sirrak'
        url = $site.siteUrl
        logo = ($site.siteUrl + 'assets/icon-512.png')
        sameAs = @($site.repoUrl)
    }
}

function New-WebSiteJson {
    return [ordered]@{
        '@type' = 'WebSite'
        name = 'Sirrak · سِرِّك'
        url = $site.siteUrl
        inLanguage = @('ar', 'en')
        publisher = (New-OrganizationJson)
    }
}

function New-LandingJsonLd($page) {
    $c = $page.lang.content
    $screens = @()
    foreach ($shot in $site.assets.screenshots.PSObject.Properties) {
        $screens += ($site.siteUrl + [string]$shot.Value)
    }
    $faqEntities = @()
    foreach ($item in @($c.faq.items)) {
        $faqEntities += [ordered]@{
            '@type' = 'Question'
            name = [string]$item.q
            acceptedAnswer = [ordered]@{ '@type' = 'Answer'; text = [string]$item.a }
        }
    }
    $app = [ordered]@{
        '@type' = 'SoftwareApplication'
        name = 'Sirrak'
        alternateName = 'سِرِّك'
        applicationCategory = 'UtilitiesApplication'
        applicationSubCategory = 'Private on-device AI assistant'
        operatingSystem = 'Android 9+'
        softwareVersion = [string]$site.product.version
        url = $page.canonical
        downloadUrl = $site.releaseUrl
        installUrl = $site.releaseUrl
        codeRepository = $site.repoUrl
        license = 'https://www.apache.org/licenses/LICENSE-2.0'
        inLanguage = @('ar', 'en')
        isAccessibleForFree = $true
        offers = [ordered]@{ '@type' = 'Offer'; price = '0'; priceCurrency = 'USD' }
        publisher = (New-OrganizationJson)
        screenshot = $screens
        image = ($site.siteUrl + 'assets/og-image.png')
    }
    $graph = @(
        $app,
        (New-WebSiteJson),
        [ordered]@{ '@type' = 'FAQPage'; inLanguage = $page.lang.htmlLang; mainEntity = $faqEntities }
    )
    return (ConvertTo-Json -InputObject ([ordered]@{ '@context' = 'https://schema.org'; '@graph' = $graph }) -Depth 12 -Compress)
}

function New-PolicyJsonLd($page, $policy) {
    $type = 'WebPage'
    if ($page.key -eq 'privacy') { $type = 'PrivacyPolicy' }
    $node = [ordered]@{
        '@type' = $type
        name = [string]$policy.title
        description = [string]$policy.description
        url = $page.canonical
        inLanguage = $page.lang.htmlLang
        datePublished = [string]$policies.updated
        dateModified = [string]$policies.updated
        isPartOf = (New-WebSiteJson)
        publisher = (New-OrganizationJson)
    }
    return (ConvertTo-Json -InputObject ([ordered]@{ '@context' = 'https://schema.org'; '@graph' = @($node) }) -Depth 10 -Compress)
}

# ---------------------------------------------------------------------------
# Landing page
# ---------------------------------------------------------------------------
function New-LandingPage($page) {
    $lang = $page.lang
    $c = $lang.content
    $prefix = $page.sitePrefix
    $lines = [System.Collections.Generic.List[string]]::new()
    $head = New-HeadBlock $page ([string]$c.meta.title) ([string]$c.meta.description) (New-LandingJsonLd $page)
    $lines.Add($head)
    $lines.Add('<body class="landing">')
    $lines.Add((New-SkipAndHeader $page))
    $lines.Add('<main id="main">')

    # Hero — the first spread, composition of numbered build instructions.
    $lines.Add('<section class="hero spread" aria-labelledby="hero-title">')
    $lines.Add('<div class="hero-grid-lines" aria-hidden="true"></div>')
    $lines.Add('<div class="wrap hero-grid">')
    $lines.Add('<div class="hero-lead">')
    $lines.Add(('<p class="hero-wordmark"><span lang="ar">{0}</span><span class="latin-mark" aria-hidden="true">{1}</span></p>' -f (Esc-Html ([string]$c.hero.wordmark)), (Esc-Html ([string]$c.hero.latin))))
    $headlineLines = @([string]$c.hero.headline -split ([char]10))
    $headlineHtml = ''
    foreach ($lineText in $headlineLines) {
        if ($headlineHtml -ne '') { $headlineHtml += '<br>' }
        $headlineHtml += (Esc-Html $lineText)
    }
    $lines.Add(('<h1 class="hero-headline" id="hero-title">{0}</h1>' -f $headlineHtml))
    $lines.Add(('<p class="hero-copy">{0}</p>' -f (Esc-Html ([string]$c.hero.lead))))
    $lines.Add('<p class="hero-actions">')
    $lines.Add(('<a class="btn btn-primary" href="{0}">{1}</a>' -f (Esc-Url ([string]$c.hero.primary.href)), (Esc-Html ([string]$c.hero.primary.label))))
    $lines.Add(('<a class="link-secondary" href="{0}">{1}</a>' -f (Esc-Url ([string]$c.hero.secondary.href)), (Esc-Html ([string]$c.hero.secondary.label))))
    $lines.Add('</p>')
    $lines.Add('<ul class="hero-meta">')
    foreach ($metaItem in @($c.hero.meta)) {
        $lines.Add(('<li><bdi>{0}</bdi></li>' -f (Esc-Html ([string]$metaItem))))
    }
    $lines.Add('</ul>')
    $lines.Add('</div>')
    $heroShot = $c.hero.screenshot
    $lines.Add('<figure class="hero-device">')
    $lines.Add('<div class="device-stage">')
    $lines.Add(('<div class="device-frame"><img src="{0}" alt="{1}" decoding="async" fetchpriority="high"></div>' -f (Esc-Url ($prefix + [string]$heroShot.src)), (Esc-Html ([string]$heroShot.alt))))
    $lines.Add('<ol class="callouts" aria-hidden="true">')
    $calloutIndex = 1
    foreach ($callout in @($heroShot.callouts)) {
        $lines.Add(('<li class="callout callout-{0}"><span class="callout-num">{0}</span></li>' -f $calloutIndex))
        $calloutIndex++
    }
    $lines.Add('</ol>')
    $lines.Add('</div>')
    $lines.Add('<figcaption class="callout-legend"><ol>')
    $calloutIndex = 1
    foreach ($callout in @($heroShot.callouts)) {
        $lines.Add(('<li><span class="callout-legend-num">{0}</span><span class="callout-legend-label">{1}</span></li>' -f $calloutIndex, (Esc-Html ([string]$callout.label))))
        $calloutIndex++
    }
    $lines.Add('</ol></figcaption>')
    $lines.Add('</figure>')
    $lines.Add('</section>')

    # Steps — numbered build instructions, numerals as structural elements.
    $lines.Add('<section class="section" id="steps" aria-labelledby="steps-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<header class="section-head">')
    $lines.Add(('<div><h2 id="steps-title">{0}</h2><div class="section-rule" aria-hidden="true"></div></div>' -f (Esc-Html ([string]$c.steps.title))))
    $lines.Add(('<p class="section-lead">{0}</p>' -f (Esc-Html ([string]$c.steps.lead))))
    $lines.Add('</header>')
    $lines.Add('<ol class="steps">')
    foreach ($step in @($c.steps.items)) {
        $lines.Add('<li class="step">')
        $lines.Add(('<p class="step-num" aria-hidden="true">{0}</p>' -f (Esc-Html ([string]$step.n))))
        $lines.Add('<div class="step-body">')
        $lines.Add(('<h3>{0}</h3>' -f (Esc-Html ([string]$step.title))))
        $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$step.body))))
        $stepCallout = Get-Opt $step 'callout'
        if ($null -ne $stepCallout) {
            $lines.Add(('<p class="step-callout">{0}</p>' -f (Esc-Html ([string]$stepCallout))))
        }
        $lines.Add('</div>')
        $lines.Add('<figure class="step-device">')
        $lines.Add(('<div class="device-frame"><img src="{0}" alt="{1}" loading="lazy" decoding="async"></div>' -f (Esc-Url ($prefix + [string]$step.screenshot.src)), (Esc-Html ([string]$step.screenshot.alt))))
        $lines.Add('</figure>')
        $lines.Add('</li>')
    }
    $lines.Add('</ol>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    # Inventory — a ledger of parts, not a card grid.
    $lines.Add('<section class="section" id="inventory" aria-labelledby="inventory-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<header class="section-head">')
    $lines.Add(('<div><h2 id="inventory-title">{0}</h2><div class="section-rule" aria-hidden="true"></div></div>' -f (Esc-Html ([string]$c.inventory.title))))
    $lines.Add(('<p class="section-lead">{0}</p>' -f (Esc-Html ([string]$c.inventory.lead))))
    $lines.Add('</header>')
    $lines.Add('<ul class="inventory">')
    foreach ($feature in @($c.inventory.items)) {
        $lines.Add('<li class="inv-row">')
        $lines.Add((Get-Icon ([string]$feature.icon)))
        $lines.Add(('<h3>{0}</h3>' -f (Esc-Html ([string]$feature.title))))
        $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$feature.body))))
        $lines.Add('</li>')
    }
    $lines.Add('</ul>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    # Evidence ledger.
    $lines.Add('<section class="section" id="evidence" aria-labelledby="evidence-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<header class="section-head">')
    $lines.Add(('<div><h2 id="evidence-title">{0}</h2><div class="section-rule" aria-hidden="true"></div></div>' -f (Esc-Html ([string]$c.evidence.title))))
    $lines.Add(('<p class="section-lead">{0}</p>' -f (Esc-Html ([string]$c.evidence.lead))))
    $lines.Add('</header>')
    $lines.Add('<ul class="ledger">')
    foreach ($row in @($c.evidence.rows)) {
        $lines.Add('<li class="ledger-row">')
        $lines.Add(('<span class="ledger-label">{0}</span>' -f (Esc-Html ([string]$row.label))))
        $lines.Add(('<span class="ledger-value">{0}</span>' -f (Esc-Html ([string]$row.value))))
        $lines.Add(('<span class="ledger-note">{0}</span>' -f (Esc-Html ([string]$row.note))))
        $lines.Add('</li>')
    }
    $lines.Add('</ul>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    # Privacy spread.
    $lines.Add('<section class="section" id="privacy" aria-labelledby="privacy-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<div class="privacy-grid">')
    $lines.Add('<div class="privacy-copy">')
    $lines.Add(('<h2 id="privacy-title">{0}</h2>' -f (Esc-Html ([string]$c.privacy.title))))
    $lines.Add('<div class="section-rule" aria-hidden="true"></div>')
    $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$c.privacy.lead))))
    $lines.Add(('<a class="link-secondary" href="{0}">{1}</a>' -f (Esc-Url (Resolve-Link ([string]$c.privacy.link.href) $page)), (Esc-Html ([string]$c.privacy.link.label))))
    $lines.Add('</div>')
    $lines.Add('<ul class="privacy-points">')
    foreach ($point in @($c.privacy.points)) {
        $lines.Add('<li class="privacy-point">')
        $lines.Add(('<h3>{0}</h3>' -f (Esc-Html ([string]$point.title))))
        $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$point.body))))
        $lines.Add('</li>')
    }
    $lines.Add('</ul>')
    $lines.Add('</div>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    # Download — the one ink-ground spread.
    $lines.Add('<section class="section section-download" id="download" aria-labelledby="download-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<div class="download-head">')
    $lines.Add(('<div><h2 id="download-title">{0}</h2><div class="section-rule" aria-hidden="true"></div></div>' -f (Esc-Html ([string]$c.download.title))))
    $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$c.download.lead))))
    $lines.Add('</div>')
    $lines.Add('<div class="download-grid">')
    $lines.Add('<div>')
    $lines.Add('<ol class="download-steps">')
    foreach ($dStep in @($c.download.steps)) {
        $lines.Add('<li class="download-step">')
        $lines.Add(('<p class="download-step-num" aria-hidden="true">{0}</p>' -f (Esc-Html ([string]$dStep.n))))
        $lines.Add('<div>')
        $lines.Add(('<h3>{0}</h3>' -f (Esc-Html ([string]$dStep.title))))
        $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$dStep.body))))
        $lines.Add('</div>')
        $lines.Add('</li>')
    }
    $lines.Add('</ol>')
    $lines.Add('<div class="download-actions">')
    $lines.Add(('<a class="btn btn-ivory" href="{0}">{1}</a>' -f (Esc-Url ([string]$c.download.primary.href)), (Esc-Html ([string]$c.download.primary.label))))
    $lines.Add(('<a class="link-secondary" href="{0}">{1}</a>' -f (Esc-Url ([string]$c.download.secondary.href)), (Esc-Html ([string]$c.download.secondary.label))))
    $lines.Add('</div>')
    $lines.Add('</div>')
    $lines.Add('<aside class="download-aside">')
    $checksTitle = Get-Opt $c.download 'checksTitle'
    if ($null -eq $checksTitle) { $checksTitle = $c.download.title }
    $lines.Add(('<h3>{0}</h3>' -f (Esc-Html ([string]$checksTitle))))
    $lines.Add('<dl class="download-checks">')
    foreach ($check in @($c.download.checks)) {
        $lines.Add('<div class="download-check">')
        $lines.Add(('<dt>{0}</dt>' -f (Esc-Html ([string]$check.label))))
        $checkDir = ''
        if ($check.PSObject.Properties.Name -contains 'ltr' -and [bool]$check.ltr) { $checkDir = ' dir="ltr"' }
        $lines.Add(('<dd{0}>{1}</dd>' -f $checkDir, (Esc-Html ([string]$check.value))))
        $lines.Add('</div>')
    }
    $lines.Add('</dl>')
    $lines.Add('</aside>')
    $lines.Add('</div>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    # Publishing status.
    $lines.Add('<section class="section" id="status" aria-labelledby="status-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<header class="section-head">')
    $lines.Add(('<div><h2 id="status-title">{0}</h2><div class="section-rule" aria-hidden="true"></div></div>' -f (Esc-Html ([string]$c.status.title))))
    $lines.Add(('<p class="section-lead">{0}</p>' -f (Esc-Html ([string]$c.status.lead))))
    $lines.Add('</header>')
    $lines.Add('<ul class="ledger">')
    foreach ($statusItem in @($c.status.items)) {
        $stateText = [string]$statusItem.state
        $stateClass = 'state-next'
        if ($stateText.ToLowerInvariant().Contains('avail') -or $stateText.Contains('متاح')) { $stateClass = 'state-available' }
        elseif ($stateText.ToLowerInvariant().Contains('plan') -or $stateText.Contains('إعداد')) { $stateClass = 'state-planned' }
        $lines.Add('<li class="ledger-row">')
        $lines.Add(('<span class="ledger-label">{0}</span>' -f (Esc-Html ([string]$statusItem.label))))
        $lines.Add(('<span class="ledger-value"><span class="state {0}">{1}</span></span>' -f $stateClass, (Esc-Html $stateText)))
        $lines.Add(('<span class="ledger-note">{0}</span>' -f (Esc-Html ([string]$statusItem.note))))
        $lines.Add('</li>')
    }
    $lines.Add('</ul>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    # FAQ.
    $lines.Add('<section class="section" id="faq" aria-labelledby="faq-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<header class="section-head">')
    $lines.Add(('<div><h2 id="faq-title">{0}</h2><div class="section-rule" aria-hidden="true"></div></div>' -f (Esc-Html ([string]$c.faq.title))))
    $lines.Add('<p class="section-lead"></p>')
    $lines.Add('</header>')
    $lines.Add('<div class="faq">')
    foreach ($faqItem in @($c.faq.items)) {
        $lines.Add('<details class="faq-item">')
        $lines.Add(('<summary><h3>{0}</h3></summary>' -f (Esc-Html ([string]$faqItem.q))))
        $lines.Add(('<div class="faq-a"><p>{0}</p></div>' -f (Esc-Html ([string]$faqItem.a))))
        $lines.Add('</details>')
    }
    $lines.Add('</div>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    # Docs.
    $lines.Add('<section class="section" id="docs" aria-labelledby="docs-title">')
    $lines.Add('<div class="wrap">')
    $lines.Add('<header class="section-head">')
    $lines.Add(('<div><h2 id="docs-title">{0}</h2><div class="section-rule" aria-hidden="true"></div></div>' -f (Esc-Html ([string]$c.docs.title))))
    $lines.Add(('<p class="section-lead">{0}</p>' -f (Esc-Html ([string]$c.docs.lead))))
    $lines.Add('</header>')
    $lines.Add('<ul class="ledger docs-ledger">')
    foreach ($docItem in @($c.docs.items)) {
        $lines.Add('<li class="ledger-row">')
        $lines.Add(('<a class="ledger-label" href="{0}">{1}</a>' -f (Esc-Url ([string]$docItem.href)), (Esc-Html ([string]$docItem.label))))
        $lines.Add(('<span class="ledger-note">{0}</span>' -f (Esc-Html ([string]$docItem.note))))
        $lines.Add('</li>')
    }
    $lines.Add('</ul>')
    $lines.Add('</div>')
    $lines.Add('</section>')

    $lines.Add('</main>')
    $lines.Add((New-Footer $page))
    $lines.Add((New-ScriptBlock))
    $lines.Add('</body>')
    $lines.Add('</html>')
    return ($lines -join $nl)
}

# ---------------------------------------------------------------------------
# Policy pages
# ---------------------------------------------------------------------------
function New-PolicyPage($page) {
    $lang = $page.lang
    $c = $lang.content
    $policySet = Get-Opt $policies $page.key
    $policy = Get-Opt $policySet $lang.code
    if ($null -eq $policy) { throw ('missing policy content for {0}/{1}' -f $page.key, $lang.code) }
    $uiSet = Get-Opt $policies 'ui'
    $ui = Get-Opt $uiSet $lang.code
    $backLabel = Get-Opt $ui 'back'
    if ($null -eq $backLabel) { $backLabel = 'Sirrak' }
    $lines = [System.Collections.Generic.List[string]]::new()
    $head = New-HeadBlock $page ([string]$policy.title) ([string]$policy.description) (New-PolicyJsonLd $page $policy)
    $lines.Add($head)
    $lines.Add('<body class="policy">')
    $lines.Add((New-SkipAndHeader $page))
    $lines.Add('<main id="main">')
    $lines.Add('<div class="wrap policy-wrap">')
    $lines.Add('<header class="policy-head">')
    $lines.Add(('<a class="policy-back" href="{0}">{1}</a>' -f (Esc-Url $page.brandHref), (Esc-Html ([string]$backLabel))))
    $lines.Add(('<p class="policy-dateline">{0}</p>' -f (Esc-Html ([string]$policy.updated))))
    $lines.Add(('<h1>{0}</h1>' -f (Esc-Html ([string]$policy.title))))
    $lines.Add('<ul class="policy-summary">')
    foreach ($summaryItem in @($policy.summary)) {
        $lines.Add(('<li>{0}</li>' -f (Esc-Html ([string]$summaryItem))))
    }
    $lines.Add('</ul>')
    $lines.Add('</header>')
    $lines.Add('<article class="policy">')
    foreach ($section in @($policy.sections)) {
        $lines.Add('<section class="policy-section">')
        $lines.Add(('<h2>{0}</h2>' -f (Esc-Html ([string]$section.heading))))
        $paras = Get-Opt $section 'paragraphs'
        if ($null -ne $paras) {
            foreach ($para in @($paras)) {
                $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$para))))
            }
        }
        $bullets = Get-Opt $section 'bullets'
        if ($null -ne $bullets) {
            $lines.Add('<ul>')
            foreach ($bullet in @($bullets)) {
                $lines.Add(('<li>{0}</li>' -f (Esc-Html ([string]$bullet))))
            }
            $lines.Add('</ul>')
        }
        $parasAfter = Get-Opt $section 'paragraphsAfter'
        if ($null -ne $parasAfter) {
            foreach ($para in @($parasAfter)) {
                $lines.Add(('<p>{0}</p>' -f (Esc-Html ([string]$para))))
            }
        }
        $lines.Add('</section>')
    }
    $lines.Add('</article>')
    $lines.Add(('<p class="policy-footnote">{0} · <a href="{1}">{2}</a></p>' -f (Esc-Html ([string]$policy.updated)), (Esc-Url $site.repoUrl), (Esc-Html ([string]$c.footer.tagline))))
    $lines.Add('</div>')
    $lines.Add('</main>')
    $lines.Add((New-Footer $page))
    $lines.Add('</body>')
    $lines.Add('</html>')
    return ($lines -join $nl)
}

# ---------------------------------------------------------------------------
# 404
# ---------------------------------------------------------------------------
function New-NotFoundPage {
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('<!doctype html>')
    $lines.Add('<html lang="en" dir="ltr">')
    $lines.Add('<head>')
    $lines.Add('<meta charset="utf-8">')
    $lines.Add('<meta name="viewport" content="width=device-width, initial-scale=1">')
    $lines.Add('<meta name="robots" content="noindex">')
    $lines.Add('<title>Page not found · سِرِّك</title>')
    $lines.Add('<meta name="theme-color" content="' + $brand.palette.ground + '">')
    $lines.Add('<link rel="icon" href="assets/favicon-32.png" sizes="32x32" type="image/png">')
    $lines.Add('<link rel="stylesheet" href="assets/site.css">')
    $lines.Add('</head>')
    $lines.Add('<body>')
    $lines.Add('<header class="site-header"><div class="wrap header-inner">')
    $lines.Add(('<a class="brand" href="./">{0}<span class="brand-name" lang="ar">{1}</span><span class="brand-latin" aria-hidden="true">{2}</span></a>' -f (Get-BrandSvg 'brand-mark'), (Esc-Html ([string]$brand.copy.arabicWordmark)), (Esc-Html ([string]$brand.copy.latinWordmark))))
    $lines.Add('</div></header>')
    $lines.Add('<main id="main"><div class="wrap notfound">')
    $lines.Add('<h1>Page not found</h1>')
    $lines.Add('<p dir="rtl" lang="ar">الصفحة غير موجودة. ربما نُقل الرابط أو تغيّر اسمه.</p>')
    $lines.Add('<p>This page does not exist. The link may have moved or been renamed.</p>')
    $lines.Add('<p><a class="link-secondary" href="./">English home</a> · <a class="link-secondary" href="ar/" lang="ar" dir="rtl">الصفحة العربية</a></p>')
    $lines.Add('</div></main>')
    $lines.Add('</body>')
    $lines.Add('</html>')
    return ($lines -join $nl)
}

# ---------------------------------------------------------------------------
# Assets
# ---------------------------------------------------------------------------
$theme = Get-Content -LiteralPath $themePath -Raw -Encoding utf8
$tokenLines = [System.Collections.Generic.List[string]]::new()
$tokenLines.Add('/* Generated by scripts/build-site.ps1 from store/graphics/brand.json — do not edit. */')
$tokenLines.Add(':root {')
foreach ($tokenName in @('ground', 'surface', 'ink', 'muted', 'lapis', 'brass', 'hairline')) {
    $tokenLines.Add(('  --{0}: {1};' -f $tokenName, [string]$brand.palette.$tokenName))
}
$tokenLines.Add('}')
$css = ($tokenLines -join $nl) + $nl + $nl + $theme
Save-Text 'assets/site.css' $css

$fontMap = @(
    @{ source = [string]$brand.fonts.displayBold; target = [string]$site.assets.fonts.amiriBold },
    @{ source = [string]$brand.fonts.displayRegular; target = [string]$site.assets.fonts.amiriRegular },
    @{ source = [string]$brand.fonts.sansRegular; target = [string]$site.assets.fonts.sansRegular },
    @{ source = [string]$brand.fonts.sansSemibold; target = [string]$site.assets.fonts.sansSemibold }
)
foreach ($font in $fontMap) { Copy-Asset $font.source $font.target }

$screensDir = Join-Path $repoRoot 'store/screenshots'
foreach ($shotFile in @(Get-ChildItem -LiteralPath $screensDir -Filter '*.png' -File)) {
    Copy-Asset ('store/screenshots/' + $shotFile.Name) ('assets/screenshots/' + $shotFile.Name)
}

foreach ($iconName in @('og-image.png', 'icon-192.png', 'icon-512.png', 'apple-touch-icon.png', 'favicon-32.png')) {
    Copy-Asset ('website/assets/' + $iconName) ('assets/' + $iconName)
}

# ---------------------------------------------------------------------------
# Render all pages
# ---------------------------------------------------------------------------
foreach ($page in $pages) {
    $html = ''
    if ($page.key -eq 'landing') { $html = New-LandingPage $page }
    else { $html = New-PolicyPage $page }
    Save-Text $page.file $html
}

Save-Text '404.html' (New-NotFoundPage)

# sitemap.xml
$lastmod = [string]$site.product.releaseDate
$sm = [System.Collections.Generic.List[string]]::new()
$sm.Add('<?xml version="1.0" encoding="UTF-8"?>')
$sm.Add('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">')
foreach ($page in $pages) {
    $sm.Add('<url>')
    $sm.Add(('  <loc>{0}</loc>' -f (Esc-Html $page.canonical)))
    $sm.Add(('  <lastmod>{0}</lastmod>' -f $lastmod))
    foreach ($alt in $langs) {
        $altHref = $site.siteUrl + $alt.path + $page.suffix
        $sm.Add(('  <xhtml:link rel="alternate" hreflang="{0}" href="{1}" />' -f $alt.htmlLang, (Esc-Html $altHref)))
    }
    $sm.Add(('  <xhtml:link rel="alternate" hreflang="x-default" href="{0}" />' -f (Esc-Html ($site.siteUrl + $page.suffix))))
    $sm.Add('</url>')
}
$sm.Add('</urlset>')
Save-Text 'sitemap.xml' (($sm -join $nl) + $nl)

# robots.txt
$robots = @(
    'User-agent: *',
    'Allow: /',
    '',
    ('Sitemap: {0}sitemap.xml' -f $site.siteUrl)
) -join $nl
Save-Text 'robots.txt' ($robots + $nl)

# manifest.webmanifest
$manifestObject = [ordered]@{
    name = 'سِرِّك · Sirrak'
    short_name = 'سِرِّك'
    description = [string]$langs[0].content.meta.description
    lang = 'ar'
    dir = 'rtl'
    start_url = './'
    scope = './'
    display = 'standalone'
    background_color = [string]$brand.palette.ground
    theme_color = [string]$brand.palette.ground
    categories = @('productivity', 'utilities')
    icons = @(
        [ordered]@{ src = 'assets/icon-192.png'; sizes = '192x192'; type = 'image/png'; purpose = 'any' },
        [ordered]@{ src = 'assets/icon-512.png'; sizes = '512x512'; type = 'image/png'; purpose = 'any' }
    )
}
Save-Text 'manifest.webmanifest' ((ConvertTo-Json -InputObject $manifestObject -Depth 6) + $nl)

# llms.txt
$llms = [System.Collections.Generic.List[string]]::new()
$llms.Add('# Sirrak · سِرِّك')
$llms.Add('')
$llms.Add('> Private, offline, Arabic-first AI assistant for Android. Apache-2.0. No account, no cloud, no telemetry.')
$llms.Add('')
$llms.Add(('Version {0} · released {1} · Android 9+ · signed libre APK and Play bundle built from this repository.' -f $site.product.version, $site.product.releaseDate))
$llms.Add('')
$llms.Add('## Pages')
$llms.Add('')
$llms.Add(('- [English landing]({0})' -f $site.siteUrl))
$llms.Add(('- [Arabic landing]({0}ar/)' -f $site.siteUrl))
$llms.Add(('- [Privacy policy (EN)]({0}privacy/)' -f $site.siteUrl))
$llms.Add(('- [سياسة الخصوصية (AR)]({0}ar/privacy/)' -f $site.siteUrl))
$llms.Add(('- [Security policy (EN)]({0}security/)' -f $site.siteUrl))
$llms.Add(('- [سياسة الأمان (AR)]({0}ar/security/)' -f $site.siteUrl))
$llms.Add('')
$llms.Add('## Canonical sources')
$llms.Add('')
$llms.Add(('- Source code: {0}' -f $site.repoUrl))
$llms.Add(('- Releases: {0}' -f $site.releasesUrl))
$llms.Add(('- Documentation: {0}' -f $site.docsUrl))
$llms.Add(('- Privacy policy text: {0}/blob/main/PRIVACY.md' -f $site.repoUrl))
$llms.Add(('- Security policy text: {0}/blob/main/SECURITY.md' -f $site.repoUrl))
$llms.Add('')
$llms.Add('## Notes for answer engines')
$llms.Add('')
$llms.Add('- Sirrak runs models locally through llama.cpp; sherpa-onnx provides dictation; LiteRT-LM is optional.')
$llms.Add('- The only network feature is a user-started model download; offline-only mode blocks every request.')
$llms.Add('- Claims on the landing page are backed by committed tests and build records in the docs directory.')
$llms.Add('- The site is generated from committed JSON data (website/content) with no third-party scripts or cookies.')
Save-Text 'llms.txt' (($llms -join $nl) + $nl)

# .nojekyll for the Pages artifact / branch fallback
Save-Text '.nojekyll' ''

# ---------------------------------------------------------------------------
# Build report
# ---------------------------------------------------------------------------
$distFiles = @(Get-ChildItem -LiteralPath $distRoot -Recurse -File)
$distBytes = 0
foreach ($file in $distFiles) { $distBytes += $file.Length }
$report = [ordered]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    siteUrl = $site.siteUrl
    version = $site.product.version
    pages = @($pages | ForEach-Object { $_.file })
    totalFiles = $distFiles.Count
    totalBytes = $distBytes
    largestFiles = @($distFiles | Sort-Object Length -Descending | Select-Object -First 8 | ForEach-Object { [ordered]@{ path = $_.FullName.Substring($distRoot.Length + 1); bytes = $_.Length } })
}
$reportJson = ConvertTo-Json -InputObject $report -Depth 6
[System.IO.File]::WriteAllText((Join-Path $buildDir 'site-build.json'), $reportJson + $nl, [System.Text.UTF8Encoding]::new($false))
Write-Output ('website built: {0} files, {1:N0} bytes' -f $distFiles.Count, $distBytes)
Write-Output ('output: {0}' -f $distRoot)

