@echo off
"%~dp0.local\tools\powershell\pwsh.exe" -NoProfile -File "%~dp0scripts\local.ps1" -Action status
