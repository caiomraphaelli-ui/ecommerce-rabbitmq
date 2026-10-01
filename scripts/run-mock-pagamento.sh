#!/bin/bash
# Executa o Mock de Pagamento (provedor externo, http://localhost:8090)
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.mockpagamento.MockPagamentoApp
