# Sobe todos os processos, cada um em uma janela (compila e gera as chaves se preciso), e abre o frontend.
# ENTER neste terminal encerra tudo.
# Uso: powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1 [-Depurar]   (portas JDWP 5005 a 5012)
param([switch]$Depurar)

$pastaScripts = $PSScriptRoot
Set-Location (Join-Path $pastaScripts "..")

function Porta-RabbitMQ {
    if ($env:RABBIT_PORT) { return [int]$env:RABBIT_PORT }
    if (Test-Path ".env") {
        $linha = Get-Content ".env" | Where-Object { $_ -match '^\s*RABBIT_PORT\s*=\s*(\d+)' } | Select-Object -First 1
        if ($linha -and $linha -match '=\s*(\d+)') { return [int]$Matches[1] }
    }
    return 5672
}

function Test-RabbitMQ($porta) {
    $cliente = New-Object System.Net.Sockets.TcpClient
    try {
        return $cliente.ConnectAsync("localhost", $porta).Wait(2000)
    } catch {
        return $false
    } finally {
        $cliente.Close()
    }
}

function Iniciar-Janela($titulo, $classe, $portaDebug) {
    if ($Depurar) {
        $comandoJava = "& '$(Join-Path $pastaScripts "run-debug.ps1")' $classe $portaDebug"
        Write-Host " -> $titulo (debug na porta $portaDebug)"
    } else {
        $comandoJava = "& '$(Join-Path $pastaScripts "run.ps1")' $classe"
        Write-Host " -> $titulo"
    }
    $comando = "`$Host.UI.RawUI.WindowTitle = '$titulo'; $comandoJava"
    return Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $comando -PassThru
}

# Encerra o processo e todos os filhos (a janela do PowerShell e o java dentro dela)
function Parar-Arvore($id) {
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" | ForEach-Object { Parar-Arvore $_.ProcessId }
    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
}

$portaRabbit = Porta-RabbitMQ
if (-not (Test-RabbitMQ $portaRabbit)) {
    Write-Host "RabbitMQ não está respondendo em localhost:$portaRabbit. Inicie o RabbitMQ (ou ajuste RABBIT_PORT no .env)."
    exit 1
}

$classePrincipal = "target\classes\com\ecommerce\principal\PrincipalApp.class"
$fonteMaisRecente = Get-ChildItem src -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$precisaCompilar = (-not (Test-Path $classePrincipal)) -or (-not (Test-Path "target\dependency")) -or
        ($fonteMaisRecente.LastWriteTime -gt (Get-Item $classePrincipal).LastWriteTime)
if ($precisaCompilar) {
    Write-Host "Compilando o projeto..."
    & (Join-Path $pastaScripts "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Falha na compilação."
        exit $LASTEXITCODE
    }
}

if (-not (Test-Path "keys\principal\private_key.pem")) {
    Write-Host "Gerando as chaves RSA..."
    & (Join-Path $pastaScripts "gerar-chaves.ps1")
}

if (-not (Test-Path ".env")) {
    Write-Host "Aviso: sem arquivo .env -> e-mails de promoção apenas simulados (veja .env.example)."
}

Write-Host "Iniciando o Mock de Pagamento e os processos que consomem eventos..."
$janelas = @()
$janelas += Iniciar-Janela "Mock de Pagamento" "com.ecommerce.mockpagamento.MockPagamentoApp" 5012
$janelas += Iniciar-Janela "Estoque" "com.ecommerce.estoque.EstoqueApp" 5006
$janelas += Iniciar-Janela "Pagamento" "com.ecommerce.pagamento.PagamentoApp" 5007
$janelas += Iniciar-Janela "Entrega" "com.ecommerce.entrega.EntregaApp" 5008
$janelas += Iniciar-Janela "Consumidor C1" "com.ecommerce.consumidores.ConsumidorC1" 5010
$janelas += Iniciar-Janela "Consumidor C2" "com.ecommerce.consumidores.ConsumidorC2" 5011
Start-Sleep -Seconds 5   # tempo para cada um conectar e declarar sua fila

Write-Host "Iniciando o Promoções e o API Gateway..."
$janelas += Iniciar-Janela "Promoções" "com.ecommerce.promocoes.PromocoesApp" 5009
$janelas += Iniciar-Janela "API Gateway" "com.ecommerce.principal.PrincipalApp" 5005
Start-Sleep -Seconds 4

Write-Host ""
Write-Host "Frontend:            http://localhost:8080"
Write-Host "Mock de Pagamento:   http://localhost:8090"
Write-Host "Painel do RabbitMQ:  http://localhost:15672 (guest/guest)"
Start-Process "http://localhost:8080"

try {
    Read-Host "Pressione ENTER para encerrar todos os processos"
} finally {
    Write-Host "Encerrando os processos..."
    foreach ($janela in $janelas) {
        Parar-Arvore $janela.Id
    }
}
