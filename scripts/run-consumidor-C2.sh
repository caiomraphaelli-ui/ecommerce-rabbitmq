#!/bin/bash
# Executa o Consumidor de Promoções C2
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.consumidores.ConsumidorC2
