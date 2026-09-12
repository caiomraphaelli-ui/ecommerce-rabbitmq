#!/bin/bash
# Executa o Microsserviço entrega
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.entrega.EntregaApp
