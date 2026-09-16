#!/bin/bash
# Inicia todos os processos do sistema em modo DEBUG, cada um com sua própria
# porta JDWP, para permitir anexar o debugger (ex.: VS Code, "Java: Attach to
# <processo>" em .vscode/launch.json) e colocar breakpoints em qualquer ponto
# do fluxo de eventos, mesmo com vários microsserviços rodando ao mesmo tempo.
#
# Portas de debug:
#   Principal........ 5005
#   Estoque.......... 5006
#   Pagamento........ 5007
#   Entrega.......... 5008
#   Promoções........ 5009
#   Consumidor C1..... 5010
#   Consumidor C2..... 5011
#
# Cada processo sobe com suspend=n: já começa a rodar normalmente, e o
# debugger pode ser anexado a qualquer momento. A saída de cada processo em
# segundo plano vai para logs/<processo>.log (acompanhe com: tail -f logs/estoque.log).
# Ao sair do Principal, todos são encerrados.
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
PIDS=()

iniciar() {
    local nome=$1
    local classe=$2
    local porta=$3
    echo " -> $nome (porta $porta, log em logs/$nome.log)"
    java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$porta" \
        -cp "$CP" "$classe" > "logs/$nome.log" 2>&1 &
    PIDS+=($!)
}

encerrar() {
    echo "Encerrando os demais processos..."
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
}

if ! (echo > /dev/tcp/localhost/5672) 2>/dev/null; then
    echo "RabbitMQ não está respondendo em localhost:5672. Inicie o RabbitMQ e tente de novo."
    exit 1
fi

CLASSE_PRINCIPAL=target/classes/com/ecommerce/principal/PrincipalApp.class
if [ ! -f "$CLASSE_PRINCIPAL" ] || [ ! -d target/dependency ] || [ -n "$(find src -type f -newer "$CLASSE_PRINCIPAL")" ]; then
    echo "Compilando o projeto..."
    bash scripts/build.sh
fi

if [ ! -f keys/principal/private_key.pem ]; then
    echo "Gerando as chaves RSA..."
    bash scripts/gerar-chaves.sh
fi

mkdir -p logs
trap encerrar EXIT

echo "Iniciando os processos que consomem eventos (modo debug)..."
iniciar estoque com.ecommerce.estoque.EstoqueApp 5006
iniciar pagamento com.ecommerce.pagamento.PagamentoApp 5007
iniciar entrega com.ecommerce.entrega.EntregaApp 5008
iniciar consumidor-C1 com.ecommerce.consumidores.ConsumidorC1 5010
iniciar consumidor-C2 com.ecommerce.consumidores.ConsumidorC2 5011
sleep 5   # tempo para cada um conectar e declarar sua fila

echo "Iniciando o Promoções (modo debug)..."
iniciar promocoes com.ecommerce.promocoes.PromocoesApp 5009
sleep 2

echo "Iniciando o Principal neste terminal (modo debug, porta 5005)..."
java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" \
    -cp "$CP" com.ecommerce.principal.PrincipalApp
