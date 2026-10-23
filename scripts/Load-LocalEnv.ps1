# Dot-source this script: . ./scripts/Load-LocalEnv.ps1
$corpscopeEnvFile = Join-Path $PSScriptRoot '../.env'
if (-not (Test-Path -LiteralPath $corpscopeEnvFile)) {
    throw 'Create .env from .env.example first.'
}
$corpscopeAllowedKeys = @('DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'DB_PORT', 'DB_URL', 'OPENDART_API_KEY', 'KRX_API_KEY')
foreach ($corpscopeLine in Get-Content -LiteralPath $corpscopeEnvFile -Encoding UTF8) {
    if ($corpscopeLine -match '^\s*(#|$)') { continue }
    $corpscopePair = $corpscopeLine -split '=', 2
    if ($corpscopePair.Count -ne 2) { throw 'Invalid .env line; use KEY=value without quotes.' }
    $corpscopeKey = $corpscopePair[0].Trim()
    if ($corpscopeKey -notin $corpscopeAllowedKeys) { throw 'Unsupported key in .env.' }
    [Environment]::SetEnvironmentVariable($corpscopeKey, $corpscopePair[1].Trim(), 'Process')
}
if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD -eq 'replace-with-your-local-password') {
    throw 'Set a local DB_PASSWORD in .env.'
}
if (-not $env:DB_URL) {
    $env:DB_URL = "jdbc:postgresql://localhost:$($env:DB_PORT)/$($env:DB_NAME)"
}
