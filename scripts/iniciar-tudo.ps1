# Inicia todos os processos do sistema na ordem recomendada:
#   1. confere se o RabbitMQ está rodando
#   2. compila o projeto (se ainda não foi compilado ou se o código mudou)
#   3. gera as chaves RSA (se ainda não existirem)
#   4. abre uma janela para cada processo que consome eventos:
#      Estoque, Pagamento, Entrega, Consumidor C1 e Consumidor C2
#   5. abre a janela do Promoções (as filas do C1/C2 já existem, nenhuma promoção se perde)
#   6. roda o Principal (menu) neste terminal
# Ao sair do Principal (opção 0 ou Ctrl+C), as outras janelas são fechadas.
#
# Uso: powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1

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

function Iniciar-Janela($titulo, $script) {
    $caminho = Join-Path $pastaScripts $script
    $comando = "`$Host.UI.RawUI.WindowTitle = '$titulo'; & '$caminho'"
    Write-Host " -> $titulo"
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

Write-Host "Iniciando os processos que consomem eventos..."
$janelas = @()
$janelas += Iniciar-Janela "Estoque" "run-estoque.ps1"
$janelas += Iniciar-Janela "Pagamento" "run-pagamento.ps1"
$janelas += Iniciar-Janela "Entrega" "run-entrega.ps1"
$janelas += Iniciar-Janela "Consumidor C1" "run-consumidor-C1.ps1"
$janelas += Iniciar-Janela "Consumidor C2" "run-consumidor-C2.ps1"
Start-Sleep -Seconds 5   # tempo para cada um conectar e declarar sua fila

Write-Host "Iniciando o Promoções..."
$janelas += Iniciar-Janela "Promoções" "run-promocoes.ps1"
Start-Sleep -Seconds 2

Write-Host "Iniciando o Principal neste terminal..."
try {
    & (Join-Path $pastaScripts "run-principal.ps1")
} finally {
    Write-Host "Encerrando os demais processos..."
    foreach ($janela in $janelas) {
        Parar-Arvore $janela.Id
    }
}
