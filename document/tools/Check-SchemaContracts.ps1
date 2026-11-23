param(
    [string]$GradleCache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$outputDir = Join-Path $repoRoot 'dst/schema-check'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$jarPatterns = @(
    'com.networknt/json-schema-validator/2.0.4',
    'com.fasterxml.jackson.core/jackson-annotations/2.18.4',
    'com.fasterxml.jackson.core/jackson-core/2.18.4',
    'com.fasterxml.jackson.core/jackson-databind/2.18.4',
    'com.ethlo.time/itu/1.14.0',
    'org.slf4j/slf4j-api/2.0.17'
)
$schemaJars = foreach ($pattern in $jarPatterns) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $GradleCache $pattern) -Recurse -Filter '*.jar')
    if ($matches.Count -ne 1) { throw "Expected one cached JAR for $pattern; resolve project dependencies first." }
    $localJar = Join-Path $outputDir $matches[0].Name
    Copy-Item -LiteralPath $matches[0].FullName -Destination $localJar -Force
    $localJar
}
$schemaClasspath = $schemaJars -join [IO.Path]::PathSeparator
& javac -encoding UTF-8 -cp $schemaClasspath -d $outputDir (Join-Path $PSScriptRoot 'SchemaContractCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Schema checker compilation failed.' }
& java -cp ($outputDir + [IO.Path]::PathSeparator + $schemaClasspath) SchemaContractCheck $repoRoot
if ($LASTEXITCODE -ne 0) { throw 'Schema contract checks failed.' }
