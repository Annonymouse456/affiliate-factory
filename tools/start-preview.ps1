param(
  [int]$Port = 8766
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Server = Join-Path $PSScriptRoot 'static-server.js'

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
  throw 'Node.js is required to run the local preview server.'
}

$existing = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
if ($existing) {
  Write-Host "Preview already appears to be running on http://127.0.0.1:$Port/"
  Start-Process "http://127.0.0.1:$Port/"
  exit 0
}

$env:AFFILIATE_FACTORY_ROOT = $Root
$env:PORT = $Port
Start-Process -FilePath (Get-Command node).Source -ArgumentList "`"$Server`"" -WindowStyle Hidden
Start-Sleep -Seconds 2

Write-Host "Affiliate Factory preview: http://127.0.0.1:$Port/"
Start-Process "http://127.0.0.1:$Port/"
