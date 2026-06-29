param(
  # Product ids whose promo image (images/promo-<id>.png) should be deployed alongside index.html.
  [string[]]$ProductId = @(),

  # Extra explicit files to deploy (paths relative to the project root, e.g. images/foo.png).
  [string[]]$Path = @(),

  # Skip deploying index.html (only push the images given via -ProductId / -Path).
  [switch]$NoIndex,

  # Do everything except the actual write (GET current sha, base64-encode, print plan). No live change.
  [switch]$DryRun,

  [string]$Repository = 'Annonymouse456/affiliate-factory',
  [string]$Branch = 'main',
  [string]$Message
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

# --- locate GitHub CLI (same candidates as deploy-video-gh.ps1) ---
$GhCandidates = @(
  "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\GitHub.cli_Microsoft.Winget.Source_8wekyb3d8bbwe\bin\gh.exe",
  "$env:ProgramFiles\GitHub CLI\gh.exe",
  "$env:ProgramFiles(x86)\GitHub CLI\gh.exe"
)
$Gh = ($GhCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1)
if (-not $Gh) {
  $GhCmd = Get-Command gh -ErrorAction SilentlyContinue
  if ($GhCmd) { $Gh = $GhCmd.Source }
}
if (-not $Gh) {
  throw 'GitHub CLI (gh) is not installed.'
}

$OldErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $Gh auth status *> $null
$AuthExitCode = $LASTEXITCODE
$ErrorActionPreference = $OldErrorActionPreference
if ($AuthExitCode -ne 0) {
  throw 'GitHub CLI is not logged in. Run: gh auth login --hostname github.com --git-protocol https --web'
}

$Token = & $Gh auth token
if (-not $Token) {
  throw 'Could not read GitHub auth token from gh.'
}

$Headers = @{
  Authorization = "Bearer $Token"
  Accept = 'application/vnd.github+json'
  'X-GitHub-Api-Version' = '2022-11-28'
  'User-Agent' = 'affiliate-factory-deploy'
}

# --- build the file list (local path -> repo path). NEVER includes videos/. ---
$Files = New-Object System.Collections.Generic.List[object]

if (-not $NoIndex) {
  $Files.Add([pscustomobject]@{ Local = (Join-Path $Root 'index.html'); Repo = 'index.html' })
}

foreach ($id in $ProductId) {
  $rel = "images/promo-$id.png"
  $Files.Add([pscustomobject]@{ Local = (Join-Path $Root ($rel -replace '/', '\')); Repo = $rel })
}

foreach ($p in $Path) {
  $rel = ($p -replace '\\', '/').TrimStart('/')
  if ($rel -like 'videos/*') {
    throw "Refusing to deploy a videos/ path ($rel). Video deploys are handled by deploy-video-gh.ps1 (Codex)."
  }
  $Files.Add([pscustomobject]@{ Local = (Join-Path $Root ($rel -replace '/', '\')); Repo = $rel })
}

if ($Files.Count -eq 0) {
  throw 'Nothing to deploy. Use -ProductId/-Path to add images, or drop -NoIndex to deploy index.html.'
}

foreach ($f in $Files) {
  if (-not (Test-Path -LiteralPath $f.Local)) {
    throw "File not found: $($f.Local)"
  }
}

if (-not $Message) {
  $Message = if ($ProductId.Count) { "Update affiliate page (index.html + promo: $($ProductId -join ', '))" } else { 'Update affiliate page' }
}

function Get-RemoteSha {
  param($RepoPath)
  $escaped = ($RepoPath -split '/' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
  $uri = "https://api.github.com/repos/$Repository/contents/$escaped`?ref=$Branch"
  try {
    $info = Invoke-RestMethod -Method Get -Uri $uri -Headers $Headers
    return $info.sha
  } catch {
    $resp = $_.Exception.Response
    if ($resp -and $resp.StatusCode -eq [System.Net.HttpStatusCode]::NotFound) {
      return $null   # new file -> create without sha
    }
    throw
  }
}

foreach ($f in $Files) {
  $sha = Get-RemoteSha -RepoPath $f.Repo
  $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($f.Local))
  $action = if ($sha) { 'update' } else { 'create' }

  if ($DryRun) {
    Write-Host "[dry-run] would $action $($f.Repo) ($([IO.FileInfo]::new($f.Local).Length) bytes)$(if($sha){" sha=$($sha.Substring(0,12))"})"
    continue
  }

  $escaped = ($f.Repo -split '/' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
  $apiBase = "https://api.github.com/repos/$Repository/contents/$escaped"
  $body = [ordered]@{ message = $Message; content = $base64; branch = $Branch }
  if ($sha) { $body.sha = $sha }
  $json = $body | ConvertTo-Json -Depth 5 -Compress
  $result = Invoke-RestMethod -Method Put -Uri $apiBase -Headers $Headers -Body $json -ContentType 'application/json'
  Write-Host "Deployed $($f.Repo) ($action). Commit: $($result.commit.sha)"
}

if ($DryRun) {
  Write-Host '[dry-run] no changes were pushed.'
} else {
  Write-Host 'Live: https://annonymouse456.github.io/affiliate-factory/'
}
