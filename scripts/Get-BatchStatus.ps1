param(
    [ValidateRange(1,9223372036854775807)][long]$ExecutionId,
    [ValidateRange(1,100)][int]$Limit = 20,
    [string]$OutputFile
)
$corpscopeOptions = @('--corpscope.operations.mode=batch', "--corpscope.operations.limit=$Limit")
if ($PSBoundParameters.ContainsKey('ExecutionId')) { $corpscopeOptions += "--corpscope.operations.execution-id=$ExecutionId" }
& (Join-Path $PSScriptRoot 'Invoke-LocalReport.ps1') -OperationArguments $corpscopeOptions -OutputFile $OutputFile
