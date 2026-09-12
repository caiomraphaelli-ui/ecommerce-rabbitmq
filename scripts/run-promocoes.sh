#!/bin/bash
# Executa o Microsserviço promocoes
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.promocoes.PromocoesApp
