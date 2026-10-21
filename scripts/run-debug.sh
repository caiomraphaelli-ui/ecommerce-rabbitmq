#!/bin/bash
# Executa uma classe do projeto com o classpath correto, abrindo uma porta
# JDWP para anexar um debugger (ex.: VS Code, IntelliJ).
# suspend=n: o processo já começa a rodar; o debugger pode ser anexado
# a qualquer momento durante a execução.
# Uso: run-debug.sh <Classe> <Porta>
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
CLASSE=$1
PORTA=$2
java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$PORTA" -cp "$CP" "$CLASSE"
