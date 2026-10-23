param(
    [Parameter(Mandatory = $true)][string[]]$OperationArguments,
    [string]$OutputFile
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Load-LocalEnv.ps1')
$corpscopeRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$corpscopeJar = Join-Path $corpscopeRoot 'build/libs/corpscope-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $corpscopeJar)) { throw 'Run ./gradlew.bat build first.' }
$corpscopeJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$corpscopeArguments = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', $corpscopeJar, '--spring.main.web-application-type=none',
    '--spring.batch.job.enabled=false', '--corpscope.batch.schedule-enabled=false',
    '--spring.flyway.enabled=false', '--spring.datasource.hikari.connection-timeout=5000',
    '--spring.main.banner-mode=off', '--logging.level.root=ERROR', '--corpscope.operations.enabled=true')
$corpscopePreviousEncoding = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $corpscopeLines = & $corpscopeJava @corpscopeArguments @OperationArguments
    $corpscopeExit = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $corpscopePreviousEncoding
}
# Select only our structured JSON output. Never echo JVM startup logs or raw failure traces.
$corpscopeJson = @($corpscopeLines | Where-Object { $_ -match '^\s*[\[{]' }) | Select-Object -Last 1
if (-not $corpscopeJson) { throw "Local report could not start (exit $corpscopeExit). Check DB configuration and Java 21." }
try { $corpscopeResult = ConvertFrom-Json $corpscopeJson -ErrorAction Stop }
catch { throw 'Local report did not return valid JSON.' }
if ($corpscopeJson.TrimStart().StartsWith('[')) {
    $corpscopeFormatted = if ($null -eq $corpscopeResult) { '[]' }
        else { ConvertTo-Json -InputObject @($corpscopeResult) -Depth 20 }
} else {
    $corpscopeFormatted = ConvertTo-Json -InputObject $corpscopeResult -Depth 20
}
if ($OutputFile) {
    $corpscopeOutputPath = [IO.Path]::GetFullPath($OutputFile)
    $corpscopeParent = Split-Path -Parent $corpscopeOutputPath
    New-Item -ItemType Directory -Force $corpscopeParent | Out-Null
    [IO.File]::WriteAllText($corpscopeOutputPath, $corpscopeFormatted, [Text.UTF8Encoding]::new($false))
}
Write-Output $corpscopeFormatted
if ($corpscopeExit -ne 0) { throw "Local verification reported a failure (exit $corpscopeExit). See the JSON result." }
