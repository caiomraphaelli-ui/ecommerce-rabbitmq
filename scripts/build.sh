#!/bin/bash
# Compila o projeto e baixa as dependências (RabbitMQ client + Gson) para target/dependency
set -e
cd "$(dirname "$0")/.."
mvn clean package
echo ""
echo "Build concluído. Classpath para execução manual:"
echo "target/classes:target/dependency/*"
