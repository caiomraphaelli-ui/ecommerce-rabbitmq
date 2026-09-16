# Executa uma classe do projeto com o classpath correto, abrindo uma porta
# JDWP para anexar um debugger (ex.: VS Code, IntelliJ).
# suspend=n: o processo já começa a rodar; o debugger pode ser anexado
# a qualquer momento durante a execução.
param(
    [Parameter(Mandatory = $true)][string]$Classe,
    [Parameter(Mandatory = $true)][int]$Porta
)
Set-Location (Join-Path $PSScriptRoot "..")
$CP = "target/classes;target/dependency/*"
java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$Porta" -cp $CP $Classe
