$ErrorActionPreference = "Stop"
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot "run.ps1"), [ref]$tokens, [ref]$errors
)
if ($errors.Count) { throw ($errors | Out-String) }
# Load only pure result functions; never execute device orchestration in this test.
foreach ($name in @("Get-SessionStartFailure", "Get-MediaTraceOutcome")) {
    $definition = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    if ($null -eq $definition) { throw "Missing function: $name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

$count = 0
function Assert-Equal($actual, $expected) {
    if ($actual -ne $expected) { throw "Expected '$expected', got '$actual'" }
    $script:count++
}

Assert-Equal (Get-SessionStartFailure "D/WarpnectDiscovery(12): event=session_start_failed reason=SystemAudioStartFailed") "SystemAudioStartFailed"
Assert-Equal (Get-SessionStartFailure "D/Other: event=session_start_failed reason=Unrelated") $null
Assert-Equal (Get-SessionStartFailure "D/WarpnectDiscovery: event=setup_failed reason=MalformedMessage") $null
Assert-Equal (Get-SessionStartFailure "D/WarpnectDiscovery: event=media_start_accepted") $null

$media = [pscustomobject]@{
    host_start_error = $null
    client_start_error = $null
    host_media_started = $false
    client_media_started = $true
    client_first_frame_decoded = $true
    client_first_frame_rendered = $false
}
Assert-Equal (Get-MediaTraceOutcome InputSessionHold $media).result "FAIL"
$media.host_media_started = $true
Assert-Equal (Get-MediaTraceOutcome InputSessionHold $media).result "PASS"
Assert-Equal ((Get-MediaTraceOutcome InputSessionHold $media).reason -match 'E2E NOT proven') $true
Assert-Equal (Get-MediaTraceOutcome MediaStartupTrace $media).result "FAIL"
$media.client_first_frame_rendered = $true
Assert-Equal (Get-MediaTraceOutcome MediaStartupTrace $media).result "PASS"
$media.host_start_error = "SystemAudioStartFailed"
Assert-Equal (Get-MediaTraceOutcome InputSessionHold $media).result "FAIL"
Assert-Equal (Get-MediaTraceOutcome MediaStartupTrace $media).reason "host Session startup failed: SystemAudioStartFailed"
$media.host_start_error = $null
$media.client_start_error = "InputPipelineStartFailed"
Assert-Equal (Get-MediaTraceOutcome InputSessionHold $media).reason "client Session startup failed: InputPipelineStartFailed"
$media.client_start_error = $null
$media.client_first_frame_decoded = $false
Assert-Equal (Get-MediaTraceOutcome InputSessionHold $media).result "FAIL"
"Media outcome tests: $count assertions PASS"
