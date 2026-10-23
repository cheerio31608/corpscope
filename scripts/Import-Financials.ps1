param(
    [ValidatePattern('^[0-9]{8}(,[0-9]{8}){0,99}$')][string]$CorpCodes = '00126380',
    [ValidateRange(2015,2100)][int]$FiscalYear = 2024,
    [ValidateSet('Q1','HALF_YEAR','Q3','ANNUAL')][string]$ReportType = 'ANNUAL',
    [ValidateSet('CFS','OFS')][string]$StatementType = 'CFS',
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-zA-Z0-9._:-]{1,100}$')][string]$RequestId,
    [switch]$Demo
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Load-LocalEnv.ps1')
$corpscopeRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$corpscopeJar = Join-Path $corpscopeRoot 'build/libs/corpscope-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $corpscopeJar)) { throw 'Run ./gradlew.bat build first.' }
if ($Demo -and -not $PSBoundParameters.ContainsKey('CorpCodes')) { $CorpCodes = '00000001' }
$corpscopeJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$corpscopeArguments = @('-jar', $corpscopeJar, '--spring.main.web-application-type=none',
    '--spring.batch.job.enabled=true', '--spring.batch.job.name=financialImportJob',
    "corpCodes=$CorpCodes", "fiscalYear=$FiscalYear,java.lang.Long", "reportType=$ReportType",
    "statementType=$StatementType", "requestId=$RequestId")
if ($Demo) { $corpscopeArguments += '--spring.profiles.active=demo' }
& $corpscopeJava @corpscopeArguments
if ($LASTEXITCODE -ne 0) { throw "Financial import failed (exit $LASTEXITCODE). Inspect Batch execution history." }
