#Requires -Version 7.4
[CmdletBinding()]
param([string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$testProject = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourcePath = Join-Path $testProject 'scripts/oer-local.ps1'
$parseTokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
# Load only pure configuration and health gates. Never invoke the script's
# process discovery, launch, stop, health, model or indexing entry points.
foreach ($name in @('Resolve-OerModelPublication','Assert-OerModelPublicationReuse','Assert-OerCudaHealth','New-OerFrozenSource')) {
    $definition = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $false)
    if (-not $definition) { throw "Missing gate $name" }
    Invoke-Expression $definition.Extent.Text
}
if (-not $EvidenceDirectory) {
    $EvidenceDirectory = Join-Path $testProject ('.local/state/oer-publication-gate-' + [guid]::NewGuid().ToString('N'))
}
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory, $testProject)
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Fresh owned evidence directory required.' }
New-Item -ItemType Directory -Path $EvidenceDirectory | Out-Null
$oerProject = $EvidenceDirectory
$oerRoot = Join-Path $EvidenceDirectory 'oer'
New-Item -ItemType Directory -Path (Join-Path $oerRoot 'model-publications') -Force | Out-Null
$checks = [Collections.Generic.List[string]]::new()
function Assert-Test([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "FAIL: $Label" }
    $checks.Add($Label)
}
function Assert-Rejected([scriptblock]$Call, [string]$Message, [string]$Label) {
    try { & $Call; throw "Accepted unexpectedly: $Label" }
    catch { Assert-Test ($_.Exception.Message.Contains($Message)) $Label }
}
$strict = Resolve-OerModelPublication -ConfigPath '' -ExplicitConfig $false -RawVlm $false
Assert-Test ($strict.Mode -eq 'strict-checkpoint-validation' -and $strict.Environment.Count -eq 4) 'implicit_missing_config_discloses_strict_mode'
Assert-Test (@($strict.Environment.Values | Where-Object { $_ -ne '' }).Count -eq 0) 'strict_mode_clears_all_four_inherited_publication_values'
Assert-Rejected { Resolve-OerModelPublication -ConfigPath 'absent.json' -ExplicitConfig $true -RawVlm $false } 'Explicit ModelPublicationConfig is missing' 'explicit_missing_config_rejected'
$raw = Resolve-OerModelPublication -ConfigPath 'absent.json' -ExplicitConfig $true -RawVlm $true
Assert-Test ($raw.Mode -eq 'not-applicable' -and $raw.Environment.Count -eq 0) 'raw_vlm_publication_lifecycle_unchanged'
$config = [ordered]@{}
foreach ($kind in @('embedding','reranker')) {
    $manifest = Join-Path $EvidenceDirectory "$kind-manifest.json"
    [IO.File]::WriteAllText($manifest, "{`"authored`":`"$kind`"}")
    $config[$kind] = [ordered]@{ manifestPath="$kind-manifest.json";
        manifestSha256=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$configPath = Join-Path $oerRoot 'model-publications/runtime.json'
$config | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $configPath -Encoding utf8
$resolved = Resolve-OerModelPublication -ConfigPath '' -ExplicitConfig $false -RawVlm $false
Assert-Test ($resolved.Mode -eq 'immutable-publication' -and $resolved.Environment.Count -eq 4) 'valid_manifests_produce_four_exact_pins'
Assert-Test ($resolved.ConfigSha256 -ceq (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash.ToLowerInvariant()) 'config_bytes_sha_recorded'
$saved = [pscustomobject]@{ Environment=[pscustomobject]$resolved.Environment }
Assert-OerModelPublicationReuse -Requested $resolved -Saved $saved
Assert-Test $true 'same_exact_pins_allow_existing_runtime'
foreach ($name in $resolved.Environment.Keys) {
    $changed = @{}
    foreach ($key in $resolved.Environment.Keys) { $changed[$key]=$resolved.Environment[$key] }
    $changed[$name] = 'changed-publication-value'
    $request = [pscustomobject]@{ Mode='immutable-publication'; Environment=$changed }
    Assert-Rejected { Assert-OerModelPublicationReuse -Requested $request -Saved $saved } 'explicitly stop and start' "changed_${name}_requires_new_runtime"
}
Assert-Rejected { Assert-OerModelPublicationReuse -Requested $resolved -Saved ([pscustomobject]@{ Environment=[pscustomobject]@{} }) } 'explicitly stop and start' 'old_unpinned_runtime_cannot_be_reused_as_published'
Assert-OerModelPublicationReuse -Requested $raw -Saved ([pscustomobject]@{})
Assert-Test $true 'raw_vlm_reuse_remains_outside_publication_gate'
$embeddingManifest = Join-Path $EvidenceDirectory 'embedding-manifest.json'
$original = [IO.File]::ReadAllBytes($embeddingManifest)
[IO.File]::WriteAllBytes($embeddingManifest, [byte[]]($original + 32))
Assert-Rejected { Resolve-OerModelPublication -ConfigPath $configPath -ExplicitConfig $true -RawVlm $false } 'differs from its explicit pin' 'actual_manifest_drift_rejected_before_runtime_reuse'
[IO.File]::WriteAllBytes($embeddingManifest, $original)
$partial = Join-Path $EvidenceDirectory 'partial.json'
@{ embedding=$config.embedding } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $partial -Encoding utf8
Assert-Rejected { Resolve-OerModelPublication -ConfigPath $partial -ExplicitConfig $true -RawVlm $false } 'requires both exact manifests' 'partial_publication_config_rejected'
$code = Get-Content -LiteralPath $sourcePath -Raw
Assert-Test ($code.IndexOf('$modelPublication = Resolve-OerModelPublication') -lt $code.IndexOf('if ($owned.Count)')) 'config_gate_precedes_owned_runtime_reuse'
Assert-Test ($code.Contains('ModelPublication=[ordered]@{ Mode=$modelPublication.Mode')) 'process_receipt_records_actual_validation_mode'
# Verify the Windows Start-Process environment behavior with a tiny process
# that prints variables. This is not a service or a model invocation.
$childScript = Join-Path $EvidenceDirectory 'print-publication-env.py'
[IO.File]::WriteAllText($childScript, "import json, os`nprint(json.dumps({name: os.environ.get(name) for name in " +
    "['CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST','CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST_SHA256','CONSENSE_RERANKER_CHECKPOINT_MANIFEST','CONSENSE_RERANKER_CHECKPOINT_MANIFEST_SHA256']}))`n")
$childOutput = Join-Path $EvidenceDirectory 'child-env.json'
$testPython = Join-Path $testProject '.local/oer/venvs/retrieval-gpu/Scripts/python.exe'
$previousHostValue = $env:CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST
try {
    $env:CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST = 'AUTHORED-unrelated-host-publication'
    Start-Process -FilePath $testPython -ArgumentList ('"' + $childScript + '"') -WindowStyle Hidden -Wait -Environment $strict.Environment -RedirectStandardOutput $childOutput
} finally { $env:CONSENSE_EMBEDDING_CHECKPOINT_MANIFEST = $previousHostValue }
$childValues = Get-Content -LiteralPath $childOutput -Raw | ConvertFrom-Json
Assert-Test (@($childValues.PSObject.Properties | Where-Object { -not [string]::IsNullOrEmpty([string]$_.Value) }).Count -eq 0) 'strict_child_environment_does_not_inherit_host_publication'
$Component='unified-advisory'; $oerTargetModule='advisory'; $oerUnified=$true
$actualCas=[pscustomobject]@{artifactReadValidation='actual-cas-unique-per-validation-session-with-exit-rehash';artifactReadValidationScope='canonical_load'}
$family=[pscustomobject]@{
    adoptedCoverage='disjoint-physical-pages-required-v1';componentContract='complete-cold-actual-cas-native-pdf-public-roots'
    crossComponentRelations='unsupported';maximumMessageBytes=16777216;producerKind='native_pdf_source_family'
    projectEndpoint='/project/native-pdf-family';projectionInputs='actual-cas-family-spec-only'
    schema='consense.native-pdf-source-family.v1';semanticAcceptanceByComposition=$false;sourcePageRenderStatus='unsupported'
    sourceRevisionKind='derived_same_original_component_set';version='same-original-complete-native-pdf-components-v1';wholeSourceAccepted=$false
}
$normal=[pscustomobject]@{
    schema='consense.normal-observed.v1';version='complete-bound-normal-observations-v1';producerKind='normal_observed'
    projectionEndpoint='/normal-observed';candidateGraphSchema='consense.canonical-candidate.v1'
    candidateGraphVersion='actual-normal-source-unit-whole-page-observations-v2'
    completeUnitPolicy='complete-bound-generation-all-units-in-source-order'
    nonTextUnitPolicy='retained-in-complete-graph-not-submitted-to-model'
    artifactReadValidation='complete-manifest-actual-cas-reconstruction-every-request'
    originalByteVerifier='java_current_source_reader';observerActor='application'
    sourceQualityAccepted=$false;semanticScopeVerified=$false;humanConfirmed=$false
    adoptionStatus='candidate_unverified';maximumMessageBytes=16777216
}
$health=[pscustomobject]@{device='cuda';runtime=[pscustomobject]@{
    embedding=[pscustomobject]@{device='cuda';dtype='float32';serialOffload=$true}
    reranker=[pscustomobject]@{device='cuda';dtype='float32';serialOffload=$true}
};oer=[pscustomobject]@{
    recipe='scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1';targetModule='advisory'
    unifiedCanonical=[pscustomobject]@{artifactReadValidation=$actualCas.artifactReadValidation;artifactReadValidationScope='canonical_load'
        sourceKinds=@('scanned_pdf','native_docx_snapshot','native_docx_collection','native_pdf','native_pdf_source_family','normal_observed')}
    nativePdfSourceFamily=$family;normalObserved=$normal
    nativeCanonical=$actualCas;nativeDocumentCollection=$actualCas;nativePdfCanonical=$actualCas
}}
Assert-OerCudaHealth $health
Assert-Test $true 'normal_observed_exact_health_allows_frozen_service_restore'
foreach($field in @('sourceQualityAccepted','semanticScopeVerified','humanConfirmed')){
    $normal.$field=$true
    Assert-Rejected {Assert-OerCudaHealth $health} 'normal observed capability' "normal_${field}_cannot_claim_acceptance"
    $normal.$field=$false
}
$normal.maximumMessageBytes=33554432
Assert-Rejected {Assert-OerCudaHealth $health} 'normal observed capability' 'normal_observed_message_cap_cannot_expand'
$normal.maximumMessageBytes=16777216
$health.oer.PSObject.Properties.Remove('normalObserved')
Assert-Rejected {Assert-OerCudaHealth $health} 'normal observed capability' 'normal_kind_without_capability_rejected'
$health.oer|Add-Member normalObserved $normal
$health.oer.unifiedCanonical.sourceKinds=@('scanned_pdf','native_docx_snapshot','native_docx_collection','native_pdf','native_pdf_source_family')
Assert-Rejected {Assert-OerCudaHealth $health} 'without its source kind' 'normal_capability_without_kind_rejected'
$health.oer.PSObject.Properties.Remove('normalObserved')
Assert-OerCudaHealth $health
Assert-Test $true 'legacy_family_health_remains_supported'
$health.oer.unifiedCanonical.sourceKinds=@('scanned_pdf','native_docx_snapshot','native_docx_collection','native_pdf')
$health.oer.PSObject.Properties.Remove('nativePdfSourceFamily')
Assert-OerCudaHealth $health
Assert-Test $true 'legacy_four_kind_health_remains_supported'
$oerToolRoot=Join-Path $testProject 'ai_consense_service/tools/vetting_eval'
$oerScript=Join-Path $oerToolRoot 'oer_unified_server.py'
$ScopeManifest=Join-Path $EvidenceDirectory 'authored-scope.json'
[IO.File]::WriteAllText($ScopeManifest,'{"authored":true}')
$frozen=New-OerFrozenSource
$frozenCoordinator=Join-Path (Split-Path -Parent (Split-Path -Parent $frozen.Script)) 'offline_delivery'
foreach($dependency in @('coordinator.py','delivery.py','request_stage.py','gpu_stage.py')){
    $copied=Join-Path $frozenCoordinator $dependency
    Assert-Test (Test-Path -LiteralPath $copied) "frozen_${dependency}_dependency_present"
    Assert-Test ((Get-FileHash -LiteralPath $copied).Hash -ceq (Get-FileHash -LiteralPath (Join-Path $testProject ('ai_consense_service/tools/offline_delivery/'+$dependency))).Hash) "frozen_${dependency}_bytes_exact"
}
$importProbe=Join-Path $EvidenceDirectory 'coordinator-import.py'
[IO.File]::WriteAllText($importProbe,"import sys, json`nsys.path.insert(0, sys.argv[1])`nimport coordinator`nprint(json.dumps({'configured':coordinator.configured({})}))`n")
$importOutput=Join-Path $EvidenceDirectory 'coordinator-import.json'
$importProcess=Start-Process -FilePath $testPython -ArgumentList ('"'+$importProbe+'" "'+$frozenCoordinator+'"') -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $importOutput
Assert-Test ($importProcess.ExitCode -eq 0 -and (Get-Content -LiteralPath $importOutput -Raw|ConvertFrom-Json).configured -eq $false) 'frozen_health_coordinator_import_without_gpu_jobs'
$receipt = [ordered]@{ status='passed'; checks=$checks; checkCount=$checks.Count;
    sourcePath=$sourcePath; sourceSha256=(Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant();
    evidenceDirectory=$EvidenceDirectory; modelInferenceCalls=0; servicesStarted=0; servicesStopped=0; databaseChanged=$false;
    validation='authored_small_manifest_actual_byte_gate_and_print_only_child_environment' }
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'receipt.json') -Encoding utf8
$receipt | ConvertTo-Json -Depth 5
