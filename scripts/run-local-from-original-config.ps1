param(
    [string]$OriginalApplicationYml = "",
    [string]$JavaHome = "",
    [switch]$Online
)

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($OriginalApplicationYml)) {
    $OriginalApplicationYml = Join-Path $projectRoot "..\src\main\resources\application.yml"
}
$sourceConfig = (Resolve-Path -LiteralPath $OriginalApplicationYml).Path

function Read-FlatYamlScalars {
    param([string]$Path)

    $values = @{}
    $parents = [System.Collections.Generic.List[string]]::new()
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        if ($rawLine -match '^\s*(#|$)') {
            continue
        }
        if ($rawLine -notmatch '^(\s*)([^:]+):(?:\s*(.*))?$') {
            continue
        }

        $indent = $Matches[1].Length
        if (($indent % 2) -ne 0) {
            throw "Unsupported YAML indentation in local source configuration"
        }
        $level = [int]($indent / 2)
        $key = $Matches[2].Trim()
        $value = if ($null -eq $Matches[3]) { "" } else { $Matches[3].Trim() }

        while ($parents.Count -gt $level) {
            $parents.RemoveAt($parents.Count - 1)
        }

        if ([string]::IsNullOrWhiteSpace($value)) {
            if ($parents.Count -eq $level) {
                $parents.Add($key)
            } else {
                $parents[$level] = $key
            }
            continue
        }

        $pathParts = @($parents.ToArray()) + $key
        $fullPath = $pathParts -join "."
        if (($value.StartsWith("'") -and $value.EndsWith("'")) -or
            ($value.StartsWith('"') -and $value.EndsWith('"'))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$fullPath] = $value
    }
    return $values
}

function Require-ConfigValue {
    param(
        [hashtable]$Values,
        [string]$Path
    )
    $value = $Values[$Path]
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required configuration is missing: $Path"
    }
    return $value
}

$config = Read-FlatYamlScalars -Path $sourceConfig

$env:SUVIA_DB_URL = Require-ConfigValue $config "spring.datasource.url"
$env:SUVIA_DB_USERNAME = Require-ConfigValue $config "spring.datasource.username"
$env:SUVIA_DB_PASSWORD = Require-ConfigValue $config "spring.datasource.password"
$env:DASHSCOPE_API_KEY = Require-ConfigValue $config "spring.ai.dashscope.api-key"
$env:SEARCH_API_KEY = Require-ConfigValue $config "searchAPI.api-key"
$env:MINIO_ENDPOINT = Require-ConfigValue $config "minio.endpoint"
$env:MINIO_ACCESS_KEY = Require-ConfigValue $config "minio.access-key"
$env:MINIO_SECRET_KEY = Require-ConfigValue $config "minio.secret-key"
$env:DASHSCOPE_CHAT_MODEL = if ($config["spring.ai.dashscope.chat.options.model"]) {
    $config["spring.ai.dashscope.chat.options.model"]
} else {
    "qwen-plus"
}
$env:MINIO_BUCKET = if ($config["minio.bucket-name"]) { $config["minio.bucket-name"] } else { "rag" }
$env:SERVER_PORT = if ($config["server.port"]) { $config["server.port"] } else { "8123" }
$env:SUVIA_SECURITY_ENABLED = "false"

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = $env:JAVA_HOME
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "Set JAVA_HOME or pass -JavaHome with a JDK 21 directory."
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path

$localRepository = (Resolve-Path -LiteralPath (Join-Path $projectRoot ".m2\repository")).Path
$mavenArguments = @("-q", "-Dmaven.repo.local=$localRepository")
if (-not $Online) {
    $mavenArguments += "-o"
}
$mavenArguments += "spring-boot:run"

Write-Host "Starting codexProject with configuration mapped from the original application.yml."
Write-Host "Secrets are loaded into this process only and will not be printed or copied."
Write-Host "Frontend: http://127.0.0.1:$($env:SERVER_PORT)/api/"
Write-Host "Swagger:  http://127.0.0.1:$($env:SERVER_PORT)/api/swagger-ui.html"

Push-Location $projectRoot
try {
    & mvn.cmd @mavenArguments
} finally {
    Pop-Location
}
