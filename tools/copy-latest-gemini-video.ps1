param(
  [Parameter(Mandatory=$true)]
  [string]$ProductId,

  [string]$Downloads = "$env:USERPROFILE\Downloads"
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$VideoDir = Join-Path $Root 'videos'
$Target = Join-Path $VideoDir "clip916-$ProductId.mp4"

New-Item -ItemType Directory -Force -Path $VideoDir | Out-Null

$Latest = Get-ChildItem -LiteralPath $Downloads -File -Filter '*.mp4' |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

if (-not $Latest) {
  throw "No .mp4 file found in $Downloads"
}

Copy-Item -LiteralPath $Latest.FullName -Destination $Target -Force
Write-Host "Copied latest Gemini video:"
Write-Host "  From: $($Latest.FullName)"
Write-Host "  To:   $Target"
