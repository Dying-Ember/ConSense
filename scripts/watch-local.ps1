#Requires -Version 7.4
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $projectRoot '.local'
$watchLog = Join-Path $localRoot 'logs\watch-local.log'
$stopMarker = Join-Path $localRoot 'state\stopped'
$lifecycle = Join-Path $PSScriptRoot 'local.ps1'
while (-not (Test-Path -LiteralPath $stopMarker)) {
    try {
        $configuration = Get-Content -LiteralPath (Join-Path $localRoot 'runtime.json') -Raw | ConvertFrom-Json
        $required = if ($configuration.Model.Enabled) { @('bonsai', 'backend', 'frontend') } else { @('backend', 'frontend') }
        $components = @(& $lifecycle -Action status)
        $missing = @($components | Where-Object { $_.Component -in $required -and -not $_.Running })
        if ($missing.Count -gt 0) {
            Add-Content -LiteralPath $watchLog -Value "$(Get-Date -Format o) Restarting exited components: $($missing.Component -join ', ')"
            & $lifecycle -Action start *>> $watchLog
        }
    }
    catch {
        Add-Content -LiteralPath $watchLog -Value "$(Get-Date -Format o) $($_.Exception.Message)"
    }
    Start-Sleep -Seconds 30
}
