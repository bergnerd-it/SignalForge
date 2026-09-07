param (
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir

Set-Location $RootDir

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "⚡ Starting FinAlly AI Trading Workstation" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$buildArgs = @("compose", "up", "-d")
if ($Build) {
    $buildArgs += "--build"
}

docker @buildArgs

Write-Host ""
Write-Host "🚀 FinAlly is up and running!" -ForegroundColor Green
Write-Host "👉 Open your browser at: http://localhost:8000" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan

Start-Process "http://localhost:8000"
