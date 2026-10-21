# Igual ao iniciar-tudo.ps1, com uma porta JDWP por processo para anexar o debugger:
# Principal 5005, Estoque 5006, Pagamento 5007, Entrega 5008, Promoções 5009, C1 5010, C2 5011, Mock 5012.
& (Join-Path $PSScriptRoot "iniciar-tudo.ps1") -Depurar
