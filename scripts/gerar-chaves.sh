#!/bin/bash
# Gera os 5 pares de chaves RSA (um por microsserviço) na pasta keys/
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.keys.KeyGeneratorTool
