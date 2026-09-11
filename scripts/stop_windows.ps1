$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir

Set-Location $RootDir

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "🛑 Stopping SignalForge AI Trading Workstation" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

docker compose down

Write-Host "✓ SignalForge stopped successfully (database volume preserved)." -ForegroundColor Green
