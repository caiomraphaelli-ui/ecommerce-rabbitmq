#!/bin/bash
# Executa o Microsserviço pagamento
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.pagamento.PagamentoApp
