# Executa uma classe do projeto com o classpath correto (separador ";" no Windows)
param([Parameter(Mandatory = $true)][string]$Classe)
Set-Location (Join-Path $PSScriptRoot "..")
$CP = "target/classes;target/dependency/*"
java -cp $CP $Classe
