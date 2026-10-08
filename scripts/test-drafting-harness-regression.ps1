#Requires -Version 7.4
[CmdletBinding()]
param([string]$OutDir)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtime = Get-Content -LiteralPath (Join-Path $projectRoot '.local/runtime.json') -Raw | ConvertFrom-Json
$testClassNames = @(
    'DraftingCapturedOutputRegressionTest'
    'DraftingCapturedBillQualificationTest'
    'DraftBillDescriptionSlotTest'
    'DraftingBillCandidateMergeTest'
    'DraftingEvidenceRecoveryBoundaryTest'
    'DraftSourceContextOrderedListTest'
    'DraftEvidenceQuotesTest'
)
if (-not $OutDir) {
    $OutDir = Join-Path $projectRoot ('.local/state/harness-regression/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
}
$resolvedOutput = [IO.Path]::GetFullPath($OutDir, $projectRoot)
if (Test-Path -LiteralPath $resolvedOutput) { throw 'Use a fresh output directory to preserve earlier test evidence.' }
$null = New-Item -ItemType Directory -Path $resolvedOutput
$priorJava = $env:JAVA_HOME
$priorPath = $env:PATH
$testExit = 1
$startedAt = [DateTime]::UtcNow
try {
    $env:JAVA_HOME = $runtime.JavaHome
    $env:PATH = "$($runtime.JavaHome)\bin;$env:PATH"
    Push-Location (Join-Path $projectRoot 'ai_consense_service')
    try {
        & (Join-Path $runtime.MavenHome 'bin/mvn.cmd') -B -ntp -DforkCount=0 "-Dtest=$($testClassNames -join ',')" test *> (Join-Path $resolvedOutput 'maven.log')
        $testExit = $LASTEXITCODE
    } finally { Pop-Location }
    $suites = @()
    foreach ($name in $testClassNames) {
        $report = Join-Path $projectRoot "ai_consense_service/target/surefire-reports/TEST-com.consense.service.drafting.$name.xml"
        if ((Test-Path -LiteralPath $report) -and (Get-Item -LiteralPath $report).LastWriteTimeUtc -ge $startedAt.AddSeconds(-2)) {
            Copy-Item -LiteralPath $report -Destination $resolvedOutput
            [xml]$xml = Get-Content -LiteralPath $report -Raw
            $suites += [ordered]@{
                name = $name; tests = [int]$xml.testsuite.tests; failures = [int]$xml.testsuite.failures
                errors = [int]$xml.testsuite.errors; skipped = [int]$xml.testsuite.skipped
            }
        }
    }
    if ($testExit -eq 0 -and ($suites.Count -ne $testClassNames.Count -or ($suites | Where-Object { $_.tests -lt 1 -or $_.failures -gt 0 -or $_.errors -gt 0 -or $_.skipped -gt 0 }))) {
        $testExit = 1
    }
    $receipt = [ordered]@{
        schemaVersion = 1; startedAt = $startedAt.ToString('o'); completedAt = [DateTime]::UtcNow.ToString('o'); exitCode = $testExit
        scope = 'Production intake and source-context replay only; no model, HTTP, adoption, service restart, or user database.'
        fixtureSha256 = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'ai_consense_service/src/test/resources/drafting/harness/captured-bill-waterproofing/fixture.json') -Algorithm SHA256).Hash
        testClassNames = $testClassNames; suites = $suites; outputDirectory = $resolvedOutput
    }
    $receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $resolvedOutput 'receipt.json') -Encoding utf8
    Get-Content -LiteralPath (Join-Path $resolvedOutput 'maven.log') -Tail 24
    Write-Output "Regression receipts: $resolvedOutput"
} finally {
    $env:JAVA_HOME = $priorJava
    $env:PATH = $priorPath
}
exit $testExit
