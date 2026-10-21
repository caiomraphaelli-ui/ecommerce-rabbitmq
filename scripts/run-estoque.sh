#!/bin/bash
# Executa o Microsserviço estoque
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.estoque.EstoqueApp
