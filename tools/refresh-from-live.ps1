$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Base = 'https://annonymouse456.github.io/affiliate-factory/'

New-Item -ItemType Directory -Force -Path $Root | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'images') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'videos') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'gemini-inputs') | Out-Null

Invoke-WebRequest -Uri $Base -OutFile (Join-Path $Root 'index.html')
$Html = Get-Content -LiteralPath (Join-Path $Root 'index.html') -Raw
$Match = [regex]::Match($Html, 'const DATA = (\[.*?\]);\s*const bar', [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $Match.Success) {
  throw 'Could not find DATA in index.html'
}

$Data = $Match.Groups[1].Value | ConvertFrom-Json
foreach ($Item in $Data) {
  if ($Item.promoImg) {
    Invoke-WebRequest -Uri ($Base + $Item.promoImg) -OutFile (Join-Path $Root $Item.promoImg)
  }

  if ($Item.clip) {
    try {
      Invoke-WebRequest -Uri ($Base + $Item.clip) -OutFile (Join-Path $Root $Item.clip) -TimeoutSec 30
    } catch {
      Write-Warning "Could not download $($Item.clip)"
    }
  }

  if ($Item.rawImg) {
    $Ext = [System.IO.Path]::GetExtension(([Uri]$Item.rawImg).AbsolutePath)
    if (-not $Ext) { $Ext = '.webp' }
    try {
      Invoke-WebRequest -Uri $Item.rawImg -OutFile (Join-Path (Join-Path $Root 'gemini-inputs') ($Item.id + $Ext)) -TimeoutSec 30
    } catch {
      Write-Warning "Could not download raw image for $($Item.id)"
    }
  }
}

Write-Host "Refreshed Affiliate Factory files in $Root"
