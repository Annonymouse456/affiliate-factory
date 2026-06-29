param(
  [int]$MaxPerDay = 3
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$StateDir = Join-Path $Root '.workflow'
$LogPath = Join-Path $StateDir 'video-deploy-log.json'
$Today = (Get-Date).ToString('yyyy-MM-dd')

if (-not (Test-Path -LiteralPath $LogPath)) {
  [pscustomobject]@{
    date = $Today
    maxPerDay = $MaxPerDay
    count = 0
    remaining = $MaxPerDay
    items = @()
  } | ConvertTo-Json -Depth 5
  exit 0
}

$Log = Get-Content -LiteralPath $LogPath -Raw | ConvertFrom-Json
$Items = @($Log | Where-Object { $_.date -eq $Today })
$Count = @($Items).Count

[pscustomobject]@{
  date = $Today
  maxPerDay = $MaxPerDay
  count = $Count
  remaining = [Math]::Max(0, $MaxPerDay - $Count)
  items = $Items
} | ConvertTo-Json -Depth 5
