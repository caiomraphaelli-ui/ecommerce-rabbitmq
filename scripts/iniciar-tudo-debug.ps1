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
# debugger pode ser anexado a qualquer momento (não precisa anexar em todos
# antes de algo acontecer). Fora isso, o comportamento é o mesmo do
# iniciar-tudo.ps1 (confere RabbitMQ, compila se necessário, gera chaves).
#
# Uso: powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo-debug.ps1

$pastaScripts = $PSScriptRoot
Set-Location (Join-Path $pastaScripts "..")

function Test-RabbitMQ {
    $cliente = New-Object System.Net.Sockets.TcpClient
    try {
        return $cliente.ConnectAsync("localhost", 5672).Wait(2000)
    } catch {
        return $false
    } finally {
        $cliente.Close()
    }
}

function Iniciar-JanelaDebug($titulo, $classe, $porta) {
    $runDebug = Join-Path $pastaScripts "run-debug.ps1"
    $comando = "`$Host.UI.RawUI.WindowTitle = '$titulo (debug :$porta)'; & '$runDebug' -Classe '$classe' -Porta $porta"
    Write-Host (" -> {0,-16} porta {1}" -f $titulo, $porta)
    return Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $comando -PassThru
}

# Encerra o processo e todos os filhos (a janela do PowerShell e o java dentro dela)
function Parar-Arvore($id) {
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" | ForEach-Object { Parar-Arvore $_.ProcessId }
    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
}

if (-not (Test-RabbitMQ)) {
    Write-Host "RabbitMQ não está respondendo em localhost:5672. Inicie o RabbitMQ e tente de novo."
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

Write-Host "Iniciando os processos que consomem eventos (modo debug)..."
$janelas = @()
$janelas += Iniciar-JanelaDebug "Estoque" "com.ecommerce.estoque.EstoqueApp" 5006
$janelas += Iniciar-JanelaDebug "Pagamento" "com.ecommerce.pagamento.PagamentoApp" 5007
$janelas += Iniciar-JanelaDebug "Entrega" "com.ecommerce.entrega.EntregaApp" 5008
$janelas += Iniciar-JanelaDebug "Consumidor C1" "com.ecommerce.consumidores.ConsumidorC1" 5010
$janelas += Iniciar-JanelaDebug "Consumidor C2" "com.ecommerce.consumidores.ConsumidorC2" 5011
Start-Sleep -Seconds 5   # tempo para cada um conectar e declarar sua fila

Write-Host "Iniciando o Promoções (modo debug)..."
$janelas += Iniciar-JanelaDebug "Promoções" "com.ecommerce.promocoes.PromocoesApp" 5009
Start-Sleep -Seconds 2

Write-Host "Iniciando o Principal neste terminal (modo debug, porta 5005)..."
try {
    & (Join-Path $pastaScripts "run-debug.ps1") -Classe "com.ecommerce.principal.PrincipalApp" -Porta 5005
} finally {
    Write-Host "Encerrando os demais processos..."
    foreach ($janela in $janelas) {
        Parar-Arvore $janela.Id
    }
}
