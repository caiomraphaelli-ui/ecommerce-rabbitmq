#!/bin/bash
# Inicia todo o sistema em modo DEBUG: cada processo abre sua própria porta
# JDWP (suspend=n, já começa rodando) para anexar o debugger a qualquer momento
# (VS Code: configurações "Attach: ..." em .vscode/launch.json).
#
# Portas de debug:
#   Principal........ 5005
#   Estoque.......... 5006
#   Pagamento........ 5007
#   Entrega.......... 5008
#   Promoções........ 5009
#   Consumidor C1..... 5010
#   Consumidor C2..... 5011
#   Mock Pagamento.... 5012
#
# Fora isso, o comportamento é o mesmo do iniciar-tudo.sh.
exec bash "$(dirname "$0")/iniciar-tudo.sh" debug
