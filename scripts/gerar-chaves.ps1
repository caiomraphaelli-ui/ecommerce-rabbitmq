# Gera os pares de chaves RSA de cada microsserviço em keys/<nome>/ (rodar uma única vez)
& (Join-Path $PSScriptRoot "run.ps1") com.ecommerce.keys.KeyGeneratorTool
