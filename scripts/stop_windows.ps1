$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir

Set-Location $RootDir

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "🛑 Stopping FinAlly AI Trading Workstation" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

docker compose down

Write-Host "✓ FinAlly stopped successfully (database volume preserved)." -ForegroundColor Green
