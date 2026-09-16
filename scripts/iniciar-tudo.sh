#!/bin/bash
# Inicia todos os processos do sistema na ordem recomendada:
#   1. confere se o RabbitMQ está rodando
#   2. compila o projeto (se ainda não foi compilado ou se o código mudou)
#   3. gera as chaves RSA (se ainda não existirem)
#   4. inicia em segundo plano os processos que consomem eventos:
#      Estoque, Pagamento, Entrega, Consumidor C1 e Consumidor C2
#   5. inicia o Promoções (as filas do C1/C2 já existem, nenhuma promoção se perde)
#   6. roda o Principal (menu) neste terminal
# A saída dos processos em segundo plano vai para logs/<processo>.log
# (acompanhe com: tail -f logs/estoque.log). Ao sair do Principal, todos são encerrados.
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
PIDS=()

iniciar() {
    local nome=$1
    local classe=$2
    echo " -> $nome (log em logs/$nome.log)"
    java -cp "$CP" "$classe" > "logs/$nome.log" 2>&1 &
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

echo "Iniciando os processos que consomem eventos..."
iniciar estoque com.ecommerce.estoque.EstoqueApp
iniciar pagamento com.ecommerce.pagamento.PagamentoApp
iniciar entrega com.ecommerce.entrega.EntregaApp
iniciar consumidor-C1 com.ecommerce.consumidores.ConsumidorC1
iniciar consumidor-C2 com.ecommerce.consumidores.ConsumidorC2
sleep 5   # tempo para cada um conectar e declarar sua fila

echo "Iniciando o Promoções..."
iniciar promocoes com.ecommerce.promocoes.PromocoesApp
sleep 2

echo "Iniciando o Principal neste terminal..."
java -cp "$CP" com.ecommerce.principal.PrincipalApp
