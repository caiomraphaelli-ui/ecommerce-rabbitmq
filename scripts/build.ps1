# Compila o projeto e baixa as dependências (RabbitMQ client + Gson) para target/dependency
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
mvn clean package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host ""
Write-Host "Build concluído. Classpath para execução manual:"
Write-Host "target/classes;target/dependency/*"
