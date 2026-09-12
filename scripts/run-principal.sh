#!/bin/bash
# Executa o Microsserviço principal
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.principal.PrincipalApp
