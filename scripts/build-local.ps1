#Requires -Version 7.4
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configuration = Get-Content -LiteralPath (Join-Path $projectRoot '.local\runtime.json') -Raw | ConvertFrom-Json
$priorJavaHome = $env:JAVA_HOME
$priorPath = $env:PATH
$priorScriptShell = $env:npm_config_script_shell
try {
    $env:JAVA_HOME = $configuration.JavaHome
    $env:PATH = "$($configuration.JavaHome)\bin;$env:PATH"
    $env:npm_config_script_shell = (Get-Process -Id $PID).Path
    Push-Location (Join-Path $projectRoot 'ai_consense_web')
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    # Old Surefire invokes cmd.exe around Java; the local path contains '&'.
    # Running these selected tests inside Maven avoids that shell quoting bug.
    Push-Location (Join-Path $projectRoot 'ai_consense_service')
    try {
        & (Join-Path $configuration.MavenHome 'bin\mvn.cmd') -B -ntp -DforkCount=0 `
            '-Dtest=DraftBusinessRulesTest,DocxNativeLayoutGuardTest,OpenAiStructuredVettingDecodingTest' package
        if ($LASTEXITCODE -ne 0) { throw 'Backend checks/build failed.' }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $priorJavaHome
    $env:PATH = $priorPath
    $env:npm_config_script_shell = $priorScriptShell
}
