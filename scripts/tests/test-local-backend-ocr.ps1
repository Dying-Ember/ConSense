#Requires -Version 7.4
[CmdletBinding()]
param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$testProject = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourcePath = Join-Path $testProject 'scripts/local.ps1'
$parseTokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors.Message -join '; ') }
$definition = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-BackendOcrEnvironment'
}, $false)
if (-not $definition) { throw 'Missing Get-BackendOcrEnvironment configuration gate.' }
# Load the pure gate only; never execute lifecycle, network or model operations.
Invoke-Expression $definition.Extent.Text
$checks = [Collections.Generic.List[string]]::new()
function Assert-Test([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "FAIL: $Label" }
    $checks.Add($Label)
}
function Assert-Rejected([object]$Ocr, [string]$Message, [string]$Label) {
    try { $null = Get-BackendOcrEnvironment $Ocr; throw "Accepted unexpectedly: $Label" }
    catch { Assert-Test ($_.Exception.Message.Contains($Message)) $Label }
}
function New-TestOcr {
    [pscustomobject]@{ Enabled=$true; Mode='local'; BaseUrl='http://127.0.0.1:18780';
        TimeoutMs=1800000; TextLayerMinChars=40 }
}
foreach ($disabled in @($null, [pscustomobject]@{Enabled=$false})) {
    $resolved = Get-BackendOcrEnvironment $disabled
    Assert-Test ($resolved.Count -eq 1 -and $resolved.CONSENSE_OCR_ENABLED -ceq 'false') 'missing_or_disabled_keeps_existing_ocr_off'
}
$valid = Get-BackendOcrEnvironment (New-TestOcr)
Assert-Test ($valid.Count -eq 5 -and $valid.CONSENSE_OCR_ENABLED -ceq 'true' -and
    $valid.CONSENSE_OCR_MODE -ceq 'local' -and $valid.CONSENSE_OCR_BASE_URL -ceq 'http://127.0.0.1:18780' -and
    $valid.CONSENSE_OCR_TIMEOUTMS -ceq '1800000' -and $valid.CONSENSE_OCR_TEXTLAYERMINCHARS -ceq '40') 'enabled_configuration_produces_exact_spring_environment'
foreach ($url in @('http://127.0.0.1:18780/', 'http://[::1]:18780')) {
    $ocr = New-TestOcr; $ocr.BaseUrl=$url
    $resolved = Get-BackendOcrEnvironment $ocr
    Assert-Test ($resolved.CONSENSE_OCR_BASE_URL -ceq $url.TrimEnd('/')) 'loopback_authority_is_normalized'
}
foreach ($value in @($null, 'true', 1)) {
    $ocr = New-TestOcr; $ocr.Enabled=$value
    Assert-Rejected $ocr 'Enabled must be a boolean' 'non_boolean_enabled_rejected'
}
foreach ($mode in @($null, 'classic', 'hubserving')) {
    $ocr = New-TestOcr; $ocr.Mode=$mode
    Assert-Rejected $ocr 'Mode must be local' 'non_local_mode_rejected'
}
foreach ($url in @($null, '127.0.0.1:18780', 'https://127.0.0.1:18780', 'http://localhost:18780',
    'http://192.168.1.2:18780', 'http://0.0.0.0:18780', 'http://127.0.0.1:18780/ocr',
    'http://user:secret@127.0.0.1:18780', 'http://127.0.0.1:18780?x=1',
    'http://127.0.0.1:18780#fragment', 'http://127.0.0.1:0', 'http://127.0.0.1',
    'http://[::1]', ' http://127.0.0.1:18780')) {
    $ocr = New-TestOcr; $ocr.BaseUrl=$url
    Assert-Rejected $ocr 'BaseUrl must be an HTTP loopback IP authority' 'unsafe_or_incomplete_endpoint_rejected'
}
foreach ($value in @($null, 0, -1, 1.5, 'NaN', 2147478648, [long]::MaxValue)) {
    $ocr = New-TestOcr; $ocr.TimeoutMs=$value
    Assert-Rejected $ocr 'TimeoutMs must be an integer from 1 to 2147478647' 'invalid_or_okhttp_overflow_timeout_rejected'
}
$ocr = New-TestOcr; $ocr.TimeoutMs=2147478647
Assert-Test ((Get-BackendOcrEnvironment $ocr).CONSENSE_OCR_TIMEOUTMS -ceq '2147478647') 'largest_safe_okhttp_call_timeout_accepted'
foreach ($value in @($null, 0, -1, 1.5, 'NaN', 2147483648)) {
    $ocr = New-TestOcr; $ocr.TextLayerMinChars=$value
    Assert-Rejected $ocr 'TextLayerMinChars must be a positive integer' 'invalid_text_threshold_rejected'
}
# Exercise the real launcher environment assembly without its side effects.
$ocrAssignment = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$ocrEnvironment'
}, $true)
$backendAssignment = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$backendEnvironment'
}, $true)
$applyOcr = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.ForEachStatementAst] -and $node.Variable.Extent.Text -eq '$ocrSetting'
}, $true)
Assert-Test ($null -ne $ocrAssignment -and $null -ne $backendAssignment -and $null -ne $applyOcr) 'launcher_has_actual_ocr_environment_wiring'
$firstMutation = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.IfStatementAst] -and $node.Clauses[0].Item1.Extent.Text -eq "`$Action -eq 'deploy-backend'"
}, $true)
Assert-Test ($ocrAssignment.Extent.StartOffset -lt $firstMutation.Extent.StartOffset) 'invalid_ocr_rejected_before_deployment_or_service_mutation'
$config=[pscustomobject]@{Model=[pscustomobject]@{Enabled=$false};BackendOcr=(New-TestOcr)}
$Action='restart-backend'; $databasePath='authored-unused'; $localRoot='authored-unused'; $frontendOrigins=@()
Invoke-Expression $ocrAssignment.Extent.Text
Invoke-Expression $backendAssignment.Extent.Text
Invoke-Expression $applyOcr.Extent.Text
Assert-Test ($backendEnvironment.CONSENSE_OCR_ENABLED -ceq 'true' -and
    $backendEnvironment.CONSENSE_OCR_MODE -ceq 'local' -and
    $backendEnvironment.CONSENSE_OCR_TIMEOUTMS -ceq '1800000' -and
    $backendEnvironment.CONSENSE_OCR_TEXTLAYERMINCHARS -ceq '40') 'normal_backend_receives_enabled_local_configuration'
$receipt=[ordered]@{status='passed';checkCount=$checks.Count;checks=$checks;
    sourceSha256=(Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant();
    servicesStarted=0;servicesStopped=0;networkCalls=0;modelCalls=0;activeRuntimeChanged=$false}
if ($EvidenceDirectory) {
    $EvidenceDirectory=[IO.Path]::GetFullPath($EvidenceDirectory, $testProject)
    if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Fresh evidence directory required.' }
    New-Item -ItemType Directory -Path $EvidenceDirectory | Out-Null
    $receipt | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'receipt.json') -Encoding utf8
}
$receipt | ConvertTo-Json -Depth 4
