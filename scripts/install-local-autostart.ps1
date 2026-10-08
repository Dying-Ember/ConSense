#Requires -Version 7.4
$ErrorActionPreference = 'Stop'
$watcher = Join-Path $PSScriptRoot 'watch-local.ps1'
$executable = (Get-Process -Id $PID).Path
$existing = Get-ScheduledTask -TaskName 'ConSense-Local' -ErrorAction SilentlyContinue
if ($existing -and -not $existing.Actions.Arguments.Contains($watcher)) {
    throw 'The task name ConSense-Local belongs to another deployment; no existing task was changed.'
}
if ($existing -and $existing.State -eq 'Running') { Stop-ScheduledTask -TaskName 'ConSense-Local' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute $executable `
    -Argument "-NoProfile -WindowStyle Hidden -File `"$watcher`"" -WorkingDirectory (Split-Path -Parent $PSScriptRoot)
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'ConSense-Local' -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Description 'Local ConSense and Bonsai API; restarts only owned exited components.' -Force | Out-Null
Start-ScheduledTask -TaskName 'ConSense-Local'
Get-ScheduledTask -TaskName 'ConSense-Local' | Select-Object TaskName, State
