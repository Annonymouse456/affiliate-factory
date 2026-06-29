param(
  [int]$Top = 0,
  [switch]$IncludeComplete,
  [switch]$NoRemote,
  [switch]$Json
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$IndexPath = Join-Path $Root 'index.html'

if (-not (Test-Path -LiteralPath $IndexPath)) {
  throw "index.html not found: $IndexPath"
}

$Html = Get-Content -LiteralPath $IndexPath -Raw
$Match = [regex]::Match($Html, 'const DATA = (\[.*?\]);\s*const bar', [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $Match.Success) {
  throw 'Could not find DATA array in index.html'
}

$Data = $Match.Groups[1].Value | ConvertFrom-Json

$Gh = $null
if (-not $NoRemote) {
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
}

function Test-RemoteFile {
  param([string]$RepoPath)
  if (-not $Gh) { return $null }
  $Escaped = ($RepoPath -split '/' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
  $Old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $Output = & $Gh api --method GET "repos/Annonymouse456/affiliate-factory/contents/$Escaped`?ref=main" 2>$null
  $Exit = $LASTEXITCODE
  $ErrorActionPreference = $Old
  if ($Exit -ne 0 -or -not $Output) { return $false }
  try {
    $Info = $Output | ConvertFrom-Json
    return [int64]$Info.size -gt 0
  } catch {
    return $false
  }
}

$Rows = foreach ($Item in $Data) {
  $Id = [string]$Item.id
  $Clip = [string]$Item.clip
  $Expected = if ($Clip) { $Clip } else { "videos/clip916-$Id.mp4" }
  $LocalPath = Join-Path $Root ($Expected -replace '/', '\')
  $LocalExists = Test-Path -LiteralPath $LocalPath
  $LocalBytes = if ($LocalExists) { (Get-Item -LiteralPath $LocalPath).Length } else { 0 }
  $RemoteExists = Test-RemoteFile -RepoPath $Expected

  $Issues = New-Object System.Collections.Generic.List[string]
  if (-not $Clip) { $Issues.Add('no_clip_field') }
  if (-not $LocalExists -or $LocalBytes -le 0) { $Issues.Add('missing_local_video') }
  if ($RemoteExists -eq $false) { $Issues.Add('missing_remote_video') }

  $Complete = ($Issues.Count -eq 0)
  if ($IncludeComplete -or -not $Complete) {
    $Score = 0
    if ($null -ne $Item.score) { $Score = [double]$Item.score }
    [pscustomobject]@{
      id = $Id
      name = [string]$Item.name
      score = $Score
      expectedVideo = $Expected
      localBytes = $LocalBytes
      remote = if ($RemoteExists -eq $null) { 'not_checked' } elseif ($RemoteExists) { 'ok' } else { 'missing' }
      status = if ($Complete) { 'ok' } else { ($Issues -join ',') }
    }
  }
}

$Rows = @($Rows | Sort-Object @{ Expression = { $_.status -eq 'ok' }; Ascending = $true }, @{ Expression = 'score'; Descending = $true }, id)
if ($Top -gt 0) {
  $Rows = @($Rows | Select-Object -First $Top)
}

if ($Json) {
  $Rows | ConvertTo-Json -Depth 5
} else {
  if ($Rows.Count -eq 0) {
    Write-Host 'All products with DATA entries have local videos. Remote was checked when gh was available.'
  } else {
    $Rows | Format-Table id, score, remote, localBytes, status, expectedVideo -AutoSize
  }
}
