# Executa o Mock de Pagamento (provedor externo, http://localhost:8090)
& (Join-Path $PSScriptRoot "run.ps1") com.ecommerce.mockpagamento.MockPagamentoApp
