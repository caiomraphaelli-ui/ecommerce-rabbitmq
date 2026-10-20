#!/bin/bash
# Executa o MS Principal / API Gateway (REST + SSE em http://localhost:8080)
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
java -cp "$CP" com.ecommerce.principal.PrincipalApp
