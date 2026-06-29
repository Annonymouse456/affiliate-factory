param(
  [Parameter(Mandatory=$true)]
  [string]$ProductId,

  [string]$Repository = 'Annonymouse456/affiliate-factory',
  [string]$Branch = 'main',
  [string]$Message,
  [int]$MaxPerDay = 3,
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$VideoPath = Join-Path $Root "videos\clip916-$ProductId.mp4"
$RepoPath = "videos/clip916-$ProductId.mp4"

if (-not (Test-Path -LiteralPath $VideoPath)) {
  throw "Video file not found: $VideoPath"
}

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

if (-not $Message) {
  $Message = "Update Gemini video for $ProductId"
}

$StateDir = Join-Path $Root '.workflow'
$LogPath = Join-Path $StateDir 'video-deploy-log.json'
$Today = (Get-Date).ToString('yyyy-MM-dd')
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

$Log = @()
if (Test-Path -LiteralPath $LogPath) {
  $Log = @(Get-Content -LiteralPath $LogPath -Raw | ConvertFrom-Json)
}
$TodayItems = @($Log | Where-Object { $_.date -eq $Today })
$AlreadyLoggedToday = @($TodayItems | Where-Object { $_.productId -eq $ProductId }).Count -gt 0
if (-not $Force -and -not $AlreadyLoggedToday -and $TodayItems.Count -ge $MaxPerDay) {
  throw "Daily video quota reached ($($TodayItems.Count)/$MaxPerDay for $Today). Use -Force only if you intentionally want to exceed the limit."
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

$EscapedRepoPath = ($RepoPath -split '/' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
$ApiBase = "https://api.github.com/repos/$Repository/contents/$EscapedRepoPath"
$Info = Invoke-RestMethod -Method Get -Uri "$ApiBase`?ref=$Branch" -Headers $Headers
$Base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($VideoPath))
$Body = [ordered]@{
  message = $Message
  content = $Base64
  sha = $Info.sha
  branch = $Branch
}

$JsonBody = $Body | ConvertTo-Json -Depth 5 -Compress
$Result = Invoke-RestMethod -Method Put -Uri $ApiBase -Headers $Headers -Body $JsonBody -ContentType 'application/json'

Write-Host "Deployed $RepoPath"
Write-Host "Commit: $($Result.commit.sha)"
Write-Host "URL: https://annonymouse456.github.io/affiliate-factory/$RepoPath"

$ExistingWithoutThis = @($Log | Where-Object { -not ($_.date -eq $Today -and $_.productId -eq $ProductId) })
$NewEntry = [pscustomobject]@{
  date = $Today
  productId = $ProductId
  repoPath = $RepoPath
  commit = $Result.commit.sha
  deployedAt = (Get-Date).ToString('s')
}
@($ExistingWithoutThis + $NewEntry) | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $LogPath -Encoding UTF8
Write-Host "Video quota: $([Math]::Min($TodayItems.Count + ($(if($AlreadyLoggedToday){0}else{1})), $MaxPerDay))/$MaxPerDay used for $Today"
