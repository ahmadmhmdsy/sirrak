#requires -Version 7.0
<#
  validate-repo.ps1 -- reusable verification of the published GitHub repository metadata and the
  live launch surface. Compared to validate-site.ps1 (which is offline and structural), this script
  checks the account-side facts that only exist on GitHub and on the web:

    - the repository settings: public visibility, default branch, license, homepage, description
      keywords and topics (all expected values come from store/repo-spec.json, not from this script)
    - Pages (workflow build type, HTTPS enforced), secret scanning + push protection, Dependabot alerts
    - branch protection on the default branch: strict required checks (the four CI jobs), linear
      history, no force pushes or deletions
    - the published release: tag, draft state, required assets, and GitHub-computed SHA-256 digests
      matching the artifact digests recorded in store/store-spec.json
    - every live site URL returning HTTP 200 (skip with -SkipLive; Pages needs a deploy to be live)

  Read-only: needs an authenticated gh CLI and network access. Exit code 0 = every check passed.
  Run after a push or a Pages deploy:

      pwsh -NoProfile -File scripts/validate-repo.ps1
      pwsh -NoProfile -File scripts/validate-repo.ps1 -SkipLive
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [int]$LiveTimeoutSeconds = 30,
    [switch]$SkipLive,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$script:checks = 0
$script:failures = New-Object 'System.Collections.Generic.List[string]'

function Check([string]$Label, [bool]$Ok, [string]$Detail) {
    $script:checks++
    if (-not $Ok) {
        $script:failures.Add($Label)
        Write-Host ('  FAIL  ' + $Label + $(if ($Detail) { '  <-- ' + $Detail } else { '' }))
    } elseif (-not $Quiet) {
        Write-Host ('  PASS  ' + $Label)
    }
}

function Section([string]$Title) {
    if (-not $Quiet) { Write-Host ''; Write-Host ('== ' + $Title + ' ' + ('=' * [Math]::Max(0, 66 - $Title.Length))) }
}

function GhJson([string[]]$Arguments) {
    $raw = & gh @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('gh ' + ($Arguments -join ' ') + ' failed: ' + ($raw -join ' ')) }
    return (($raw | Out-String) | ConvertFrom-Json)
}

$repoRoot = [System.IO.Path]::GetFullPath($RepoRoot)
$specPath = Join-Path $repoRoot 'store/repo-spec.json'
if (-not (Test-Path -LiteralPath $specPath)) { throw 'missing store/repo-spec.json' }
$spec = Get-Content -LiteralPath $specPath -Raw | ConvertFrom-Json
$site = Get-Content -LiteralPath (Join-Path $repoRoot 'website/site.json') -Raw | ConvertFrom-Json
$storeSpec = Get-Content -LiteralPath (Join-Path $repoRoot 'store/store-spec.json') -Raw | ConvertFrom-Json
$slug = [string]$spec.repository

Section 'Cross-checks against the committed data files'
Check 'website/site.json homepage matches store/repo-spec.json' ((($site.siteUrl).TrimEnd('/') + '/') -eq ([string]$spec.homepage)) ([string]$site.siteUrl)
$expectedRepoUrl = 'https://github.com/' + $slug
Check 'website/site.json repository URL matches the spec repository' (([string]$site.repoUrl).TrimEnd('/') -eq $expectedRepoUrl) ([string]$site.repoUrl)

Section 'Repository settings'
$repo = GhJson @('api', ('repos/' + $slug))
Check 'repository is public' ($repo.visibility -eq 'public') ([string]$repo.visibility)
Check 'default branch matches the spec' ($repo.default_branch -eq [string]$spec.defaultBranch) ([string]$repo.default_branch)
Check 'license is detected as expected' ($repo.license.spdx_id -eq [string]$spec.license) ([string]$repo.license.spdx_id)
Check 'homepage matches the spec' ($repo.homepage -eq [string]$spec.homepage) ([string]$repo.homepage)
$description = [string]$repo.description
$missingKeywords = @($spec.descriptionKeywords | Where-Object { $description -notmatch [regex]::Escape([string]$_) })
Check 'description is honest and complete' ($description.Length -ge 60 -and $description.Length -le 350 -and $missingKeywords.Count -eq 0) ($missingKeywords -join ', ')
$topics = @($repo.topics | ForEach-Object { [string]$_ })
$missingTopics = @($spec.topics | Where-Object { $topics -notcontains [string]$_ })
Check 'topics include every expected topic' ($missingTopics.Count -eq 0) ($missingTopics -join ', ')
Check 'topics are lowercase GitHub-valid slugs' (@($topics | Where-Object { $_ -notmatch '^[a-z0-9][a-z0-9-]{0,49}$' }).Count -eq 0) ''

Section 'Pages and repository security'
$pages = GhJson @('api', ('repos/' + $slug + '/pages'))
Check 'Pages is enabled with the workflow build type' ([string]$pages.build_type -eq [string]$spec.pages.buildType) ([string]$pages.build_type)
Check 'Pages enforces HTTPS' ([bool]$pages.https_enforced -eq [bool]$spec.pages.httpsEnforced) ([string]$pages.https_enforced)
$security = $repo.security_and_analysis
Check 'secret scanning is enabled' ([string]$security.secret_scanning.status -eq [string]$spec.security.secretScanning) ([string]$security.secret_scanning.status)
Check 'secret scanning push protection is enabled' ([string]$security.secret_scanning_push_protection.status -eq [string]$spec.security.secretScanningPushProtection) ([string]$security.secret_scanning_push_protection.status)
$alertsRaw = & gh api -i ('repos/' + $slug + '/vulnerability-alerts') 2>&1
$alertsOk = (($alertsRaw | Out-String) -match '204')
Check 'Dependabot vulnerability alerts are enabled' ([bool]$alertsOk -eq [bool]$spec.security.vulnerabilityAlerts) ''

Section 'Branch protection on the default branch'
$protection = GhJson @('api', ('repos/' + $slug + '/branches/' + [string]$spec.defaultBranch + '/protection'))
Check 'required status checks are strict (up to date before merge)' ([bool]$protection.required_status_checks.strict -eq [bool]$spec.branchProtection.strict) ([string]$protection.required_status_checks.strict)
$required = @($protection.required_status_checks.contexts | ForEach-Object { [string]$_ } | Sort-Object)
$expectedChecks = @($spec.branchProtection.requiredChecks | ForEach-Object { [string]$_ } | Sort-Object)
Check 'every expected CI job is a required check' ((($required -join '|')) -eq (($expectedChecks -join '|'))) ($required -join ', ')
Check 'linear history is required' ([bool]$protection.required_linear_history.enabled -eq [bool]$spec.branchProtection.linearHistory) ''
Check 'force pushes are blocked' ([bool]$protection.allow_force_pushes.enabled -eq [bool]$spec.branchProtection.allowForcePushes) ''
Check 'branch deletion is blocked' ([bool]$protection.allow_deletions.enabled -eq [bool]$spec.branchProtection.allowDeletions) ''

Section 'Published release and artifact digests'
$release = GhJson @('release', 'view', [string]$spec.release.tag, '--json', 'isDraft,isPrerelease,assets,tagName')
Check 'release is published (not a draft)' (-not [bool]$release.isDraft) ([string]$release.isDraft)
$assetNames = @($release.assets | ForEach-Object { [string]$_.name })
$missingAssets = @($spec.release.requiredAssets | Where-Object { $assetNames -notcontains [string]$_ })
Check 'release carries every required asset' ($missingAssets.Count -eq 0) ($missingAssets -join ', ')
if ($spec.release.verifyDigests) {
    foreach ($artifact in $storeSpec.release.artifacts) {
        $expectedName = [System.IO.Path]::GetFileName([string]$artifact.path)
        $asset = @($release.assets | Where-Object { $_.name -eq $expectedName }) | Select-Object -First 1
        if ($null -eq $asset) {
            Check ('release asset digest: ' + $expectedName) $false 'asset missing'
            continue
        }
        $digest = [string]$asset.digest
        $expectedDigest = 'sha256:' + ([string]$artifact.sha256).ToLowerInvariant()
        Check ('release asset digest matches store/store-spec.json: ' + $expectedName) ($digest -eq $expectedDigest) ($digest)
        if ($null -ne $artifact.bytes) {
            Check ('release asset size matches store/store-spec.json: ' + $expectedName) ([int64]$asset.size -eq [int64]$artifact.bytes) ([string]$asset.size)
        }
    }
}

if (-not $SkipLive) {
    Section 'Live site URLs'
    $base = ([string]$spec.homepage).TrimEnd('/') + '/'
    foreach ($path in @($spec.liveUrls)) {
        $url = $base + [string]$path
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $LiveTimeoutSeconds
            Check ('live URL returns 200: /' + [string]$path) ($response.StatusCode -eq 200 -and $response.RawContentLength -gt 0) ([string]$response.StatusCode)
        } catch {
            Check ('live URL returns 200: /' + [string]$path) $false $_.Exception.Message
        }
    }
} else {
    Section 'Live site URLs'
    Check 'live URL checks' $true 'skipped (-SkipLive)'
}

Write-Host ''
if ($script:failures.Count -eq 0) {
    Write-Host ('repo validation: ' + $script:checks + ' checks, 0 failed')
    Write-Host 'all repository checks passed.'
    exit 0
}
Write-Host ('repo validation: ' + $script:checks + ' checks, ' + $script:failures.Count + ' failed')
Write-Host 'failed checks:'
foreach ($failure in $script:failures) { Write-Host ('  - ' + $failure) }
exit 1
