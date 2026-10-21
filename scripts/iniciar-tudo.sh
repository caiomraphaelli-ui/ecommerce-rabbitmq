#!/bin/bash
# Sobe todos os processos (compila e gera as chaves se preciso) e abre o frontend em http://localhost:8080.
# Logs em logs/<processo>.log. Ctrl+C encerra tudo.
# Uso: ./scripts/iniciar-tudo.sh [debug]   (debug: portas JDWP 5005 a 5012)
set -e
cd "$(dirname "$0")/.."
CP="target/classes:target/dependency/*"
DEBUG=$1
PIDS=()

iniciar() {
    local nome=$1
    local classe=$2
    local porta_debug=$3
    local opcoes=()
    if [ "$DEBUG" = "debug" ]; then
        opcoes+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$porta_debug")
        echo " -> $nome (debug na porta $porta_debug, log em logs/$nome.log)"
    else
        echo " -> $nome (log em logs/$nome.log)"
    fi
    java "${opcoes[@]}" -cp "$CP" "$classe" > "logs/$nome.log" 2>&1 &
    PIDS+=($!)
}

encerrar() {
    echo ""
    echo "Encerrando todos os processos..."
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
}

PORTA_RABBIT=$(grep -E '^RABBIT_PORT=' .env 2>/dev/null | cut -d= -f2 | tr -d '[:space:]' || true)
PORTA_RABBIT=${RABBIT_PORT:-${PORTA_RABBIT:-5672}}
if ! (echo > /dev/tcp/localhost/$PORTA_RABBIT) 2>/dev/null; then
    echo "RabbitMQ não está respondendo em localhost:$PORTA_RABBIT. Inicie o RabbitMQ (ou ajuste RABBIT_PORT no .env)."
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

if [ ! -f .env ]; then
    echo "Aviso: sem arquivo .env -> e-mails de promoção apenas simulados (veja .env.example)."
fi

mkdir -p logs
trap encerrar EXIT

echo "Iniciando o Mock de Pagamento e os processos que consomem eventos..."
iniciar mock-pagamento com.ecommerce.mockpagamento.MockPagamentoApp 5012
iniciar estoque com.ecommerce.estoque.EstoqueApp 5006
iniciar pagamento com.ecommerce.pagamento.PagamentoApp 5007
iniciar entrega com.ecommerce.entrega.EntregaApp 5008
iniciar consumidor-C1 com.ecommerce.consumidores.ConsumidorC1 5010
iniciar consumidor-C2 com.ecommerce.consumidores.ConsumidorC2 5011
sleep 5   # tempo para cada um conectar e declarar sua fila

echo "Iniciando o Promoções e o API Gateway..."
iniciar promocoes com.ecommerce.promocoes.PromocoesApp 5009
iniciar principal com.ecommerce.principal.PrincipalApp 5005
sleep 3

echo ""
echo "Frontend:            http://localhost:8080"
echo "Mock de Pagamento:   http://localhost:8090"
echo "Painel do RabbitMQ:  http://localhost:15672 (guest/guest)"
echo "Ctrl+C encerra tudo. Log do API Gateway:"
echo ""
if command -v open > /dev/null; then
    open http://localhost:8080
elif command -v xdg-open > /dev/null; then
    xdg-open http://localhost:8080 > /dev/null 2>&1 || true
fi
tail -n +1 -f logs/principal.log
