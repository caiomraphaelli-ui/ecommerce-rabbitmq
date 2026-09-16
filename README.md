# Sistema Distribuído de E-commerce — Microsserviços + RabbitMQ + Assinatura Digital

Trabalho de Sistemas Distribuídos (UTFPR) — implementado em **Java**, seguindo à risca
o enunciado: arquitetura orientada a eventos (EDA), comunicação **exclusivamente**
via RabbitMQ (sem chamadas diretas entre processos), duas exchanges (`eCommerce`
direct e `Promoções` topic, sem fanout), 5 microsserviços, 2 consumidores de
promoções e assinatura digital com criptografia assimétrica (RSA) em todo evento.

## 1. Arquitetura

```
                         Exchange "eCommerce" (direct)
   MS Principal --publica--> pedido.criado -----------------> fila.estoque   -> MS Estoque
   MS Principal --publica--> pedido.excluido ----------------> fila.estoque   -> MS Estoque
   MS Estoque   --publica--> pedido.estoque_ok --------------> fila.principal -> MS Principal
   MS Estoque   --publica--> pedido.estoque_ok --------------> fila.pagamento -> MS Pagamento
   MS Estoque   --publica--> estoque.indisponivel -----------> fila.principal -> MS Principal
   MS Pagamento --publica--> pagamento.aprovado -------------> fila.principal -> MS Principal
   MS Pagamento --publica--> pagamento.aprovado -------------> fila.entrega   -> MS Entrega
   MS Pagamento --publica--> pagamento.recusado -------------> fila.principal -> MS Principal
   MS Entrega   --publica--> pedido.enviado ------------------> fila.principal -> MS Principal

                         Exchange "Promoções" (topic)
   MS Promoções --publica--> promocao.categoria.A/B/C --------> fila.c1 (bind A, B)      -> Consumidor C1
                                                        \------> fila.c2 (bind *)         -> Consumidor C2
```

Cada consumidor tem sua própria fila, conforme exigido. Nenhum processo chama
outro diretamente — tudo passa pelo RabbitMQ.

### Fluxo de um pedido

1. Usuário faz um pedido no terminal do **MS Principal** → publica `pedido.criado`.
2. **MS Estoque** consome, verifica disponibilidade:
   - disponível → reserva/baixa estoque → publica `pedido.estoque_ok`;
   - indisponível → publica `estoque.indisponivel`.
3. Se `estoque.indisponivel`, o **MS Principal** publica `pedido.excluido` (o Estoque
   não tinha reservado nada, então não há o que devolver).
4. Se `pedido.estoque_ok`, **MS Pagamento** consome e simula aprovação/recusa
   (variável aleatória, 70% de aprovação):
   - aprovado → publica `pagamento.aprovado`;
   - recusado → publica `pagamento.recusado`.
5. Se `pagamento.recusado`, **MS Principal** publica `pedido.excluido` → **MS Estoque**
   devolve os itens reservados.
6. Se `pagamento.aprovado`, **MS Entrega** consome, emite a nota fiscal (simulado) e
   publica `pedido.enviado`.
7. **MS Principal** atualiza o status do pedido a cada evento consumido.
8. O usuário também pode excluir um pedido manualmente pelo menu (opção 3),
   o que publica `pedido.excluido` diretamente.

### Criptografia assimétrica (RSA 2048 bits)

- Cada microsserviço tem sua própria pasta `keys/<nome>/`, com a sua chave
  privada (`private_key.pem`) e as chaves públicas de **todos os demais**
  microsserviços (`public_keys/<outro>.pem`). Cada processo lê apenas a própria pasta.
- Ao publicar um evento, o produtor:
  1. gera o hash SHA-256 do conteúdo do evento (todos os campos do envelope:
     `eventId`, `exchange`, `routingKey`, `producer`, `timestamp` e `payload`);
  2. assina o hash com sua **chave privada** (RSA, padrão PKCS#1 v1.5);
  3. coloca a assinatura, em Base64, no campo `Signature` do envelope.
- Ao consumir, o processo:
  1. obtém a **chave pública** do produtor indicado no envelope;
  2. confere se esse produtor é o responsável por aquele evento e se a
     exchange/routing key do envelope são as mesmas da entrega;
  3. recalcula o hash e verifica a assinatura (autenticidade e integridade);
  4. **só processa o evento se tudo for válido**; caso contrário, descarta.
- Os Consumidores C1/C2 seguem a mesma regra. Eles têm as pastas
  `keys/consumidor-c1` e `keys/consumidor-c2` só com chaves públicas, pois não
  publicam nada.

Veja `com.ecommerce.common.CryptoUtils`, `Envelope`, `KeyStoreManager` e `EventBus`.

## 2. Estrutura do projeto

```
ecommerce-rabbitmq/
├── pom.xml
├── scripts/                     -> scripts prontos para build e execução
├── src/main/java/com/ecommerce/
│   ├── common/                  -> ProcessoMensageria, TratadorEvento, Envelope, CryptoUtils,
│   │                                KeyStoreManager, EventBus, RabbitConfig, Catalogo,
│   │                                Produto, ItemPedido, Payloads
│   ├── keys/KeyGeneratorTool    -> gera os 5 pares de chave RSA e as pastas de chaves
│   ├── principal/                -> MS Principal (terminal + menu)
│   ├── estoque/                  -> MS Estoque
│   ├── pagamento/                -> MS Pagamento
│   ├── entrega/                  -> MS Entrega
│   ├── promocoes/                -> MS Promoções
│   └── consumidores/              -> ConsumidorPromocoes, ConsumidorC1, ConsumidorC2
└── keys/                          -> gerado em tempo de execução (não versionar!)
```

### Organização do código (orientação a objetos)

- **`ProcessoMensageria`** (classe abstrata): base de todos os processos. Abre a
  conexão com o RabbitMQ, carrega as chaves, declara exchanges e filas, publica
  eventos assinados e consome filas, validando cada evento antes de chamar o
  tratador certo. Cada subclasse implementa `iniciar()`.
- **`TratadorEvento<T>`** (interface): o que fazer com um evento válido de um
  tipo. Cada processo registra um tratador por routing key com
  `registrarTratador(...)`, normalmente como referência de método
  (ex.: `this::tratarPedidoCriado`).
- Os 5 microsserviços (`PrincipalApp`, `EstoqueApp`, `PagamentoApp`,
  `EntregaApp`, `PromocoesApp`) estendem `ProcessoMensageria`.
- **`ConsumidorPromocoes`** (classe abstrata): lógica comum dos consumidores de
  promoções. `ConsumidorC1` e `ConsumidorC2` só informam a fila e as binding keys.

## 3. Pré-requisitos

- **Java 11+** (JDK)
- **Maven 3.6+**
- **RabbitMQ** rodando em `localhost:5672` (usuário/senha padrão `guest`/`guest`)

Se não tiver o RabbitMQ instalado, a forma mais rápida é via Docker:

```bash
docker run -d --hostname rabbit-ecommerce --name rabbit-ecommerce \
  -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

O painel de administração fica em http://localhost:15672 (guest/guest) — muito
útil para ver as exchanges, filas e bindings sendo criados.

Se o RabbitMQ estiver em outro host/porta/credenciais, use system properties:

```bash
java -Drabbit.host=meuhost -Drabbit.port=5672 -Drabbit.user=user -Drabbit.pass=senha -cp ... <classe>
```

## 4. Como rodar

### 4.1. Build

```bash
cd ecommerce-rabbitmq
./scripts/build.sh
```

Isso roda `mvn clean package`, compila as classes em `target/classes` e baixa
`amqp-client` e `gson` para `target/dependency` (usado no classpath de execução).

No Windows (PowerShell), cada script `.sh` tem um equivalente `.ps1` na mesma
pasta (ex.: `.\scripts\build.ps1`, `.\scripts\run-estoque.ps1`).

### 4.2. Gerar as chaves RSA (rodar uma única vez)

```bash
./scripts/gerar-chaves.sh
```

Isso cria a pasta `keys/` com uma subpasta por microsserviço, cada uma contendo
a própria `private_key.pem` e a pasta `public_keys/` com as chaves públicas de
todos os demais microsserviços. Também cria `keys/consumidor-c1` e
`keys/consumidor-c2`, só com as chaves públicas.

### 4.3. Subir os processos (cada um em um terminal separado)

Recomenda-se iniciar primeiro os microsserviços que **consomem** eventos
(assim as filas e bindings já existem quando o Principal começar a publicar
pedidos), mas nenhum deles trava se algum publicador vier depois:

```bash
./scripts/run-estoque.sh
./scripts/run-pagamento.sh
./scripts/run-entrega.sh
./scripts/run-consumidor-C1.sh
./scripts/run-consumidor-C2.sh
./scripts/run-promocoes.sh
./scripts/run-principal.sh      # terminal interativo — use este por último
```

Ou, para iniciar tudo de uma vez na ordem acima:

```bash
./scripts/iniciar-tudo.sh                                            # Linux/Mac
powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1    # Windows
```

O script confere se o RabbitMQ está rodando, compila o projeto e gera as
chaves se for preciso, inicia os consumidores, depois o Promoções e, por
último, o Principal no próprio terminal. No Windows cada processo abre em uma
janela própria; no Linux/Mac eles rodam em segundo plano com a saída em
`logs/<processo>.log`. Ao sair do Principal, os demais processos são encerrados.

No terminal do **Principal**, use o menu para visualizar produtos, realizar
pedidos, excluir pedidos e consultar o status. Os demais terminais vão logando
o processamento de cada evento (e o painel do RabbitMQ mostra as filas
enchendo/esvaziando em tempo real).

### 4.4. Rodando sem Maven (alternativa)

Se preferir compilar manualmente com `javac`, baixe (ou já tenha em cache
local) `amqp-client-5.21.0.jar` e `gson-2.11.0.jar` e compile assim:

```bash
javac -cp "amqp-client-5.21.0.jar:gson-2.11.0.jar" -d target/classes \
  $(find src/main/java -name "*.java")

java -cp "target/classes:amqp-client-5.21.0.jar:gson-2.11.0.jar" \
  com.ecommerce.principal.PrincipalApp
```

## 5. Mapeamento com o enunciado

| Requisito do PDF | Onde está implementado |
|---|---|
| Exchange `eCommerce` (direct) | `RabbitConfig.EXCHANGE_ECOMMERCE`, declarada em cada MS |
| Exchange `Promoções` (topic) | `RabbitConfig.EXCHANGE_PROMOCOES`, declarada em `PromocoesApp`, `ConsumidorC1/C2` |
| Sem uso de fanout | Nenhuma exchange fanout é criada em nenhum lugar do código |
| Routing keys hierárquicas + bindings | `pedido.*`, `pagamento.*`, `estoque.*`, `promocao.categoria.*` |
| Cada consumidor com fila própria | `fila.principal`, `fila.estoque`, `fila.pagamento`, `fila.entrega`, `fila.c1`, `fila.c2` |
| C1 interesse em categorias A e B | `ConsumidorC1` faz bind explícito em `promocao.categoria.A` e `.B` |
| C2 interesse em todas categorias | `ConsumidorC2` faz bind com `promocao.categoria.*` |
| Consumidores de promoção não chamam microsserviços | `ConsumidorC1`/`C2` só têm dependência do RabbitMQ |
| MS Principal: interação via terminal | `PrincipalApp.executarMenu()` |
| MS Principal publica `pedido.criado` | `PrincipalApp.realizarPedido()` |
| MS Principal consome os 5 eventos de status | `PrincipalApp.iniciar()` registra um tratador por evento (`aoConfirmarEstoque`, `aoAprovarPagamento`, ...) |
| MS Principal publica `pedido.excluido` em indisponibilidade/recusa | `PrincipalApp.aoFaltarEstoque()` / `aoRecusarPagamento()` |
| MS Estoque consome `pedido.criado`/`pedido.excluido` | `EstoqueApp.tratarPedidoCriado/tratarPedidoExcluido` |
| MS Estoque publica `pedido.estoque_ok`/`estoque.indisponivel` | idem |
| MS Pagamento consome `pedido.estoque_ok`, simula aprovação | `PagamentoApp` |
| MS Entrega consome `pagamento.aprovado`, publica `pedido.enviado` | `EntregaApp` |
| MS Promoções gera e publica promoções aleatórias | `PromocoesApp` |
| Assinatura digital (hash + assinatura com chave privada) | `CryptoUtils.gerarHash` e `CryptoUtils.assinar`, chamados em `EventBus.publicar` |
| Assinatura no campo Signature do envelope | `Envelope.signature` (serializado como `Signature`) |
| Validação da assinatura (chave pública, descarta se inválida) | `CryptoUtils.verificar` e `RabbitConfig.produtorAutorizado`, chamados em `EventBus.receber` |
| Microsserviços possuem as chaves públicas de todos os demais | `KeyStoreManager` carrega as 4 chaves públicas ao iniciar |
| Pasta por microsserviço com as chaves públicas | `keys/<nome>/public_keys/`, geradas por `KeyGeneratorTool` |
| Desenvolvimento em dupla | (a cargo da dupla — dividam por camada: ex. um foca em Estoque/Pagamento/Entrega, outro em Principal/Promoções/Consumidores/criptografia) |

## 6. Sugestões para a defesa

- Use o painel do RabbitMQ (`localhost:15672`) para mostrar, ao vivo, as
  exchanges `eCommerce`/`Promoções`, as filas e os bindings hierárquicos.
- Provoque cada cenário manualmente: peça um produto em quantidade maior que o
  estoque (10 unidades por padrão) para ver `estoque.indisponivel`; peça
  algumas vezes até cair em `pagamento.recusado` (30% de chance) para ver o
  fluxo de cancelamento e devolução de estoque.
- Edite manualmente um `payload` no meio do caminho (ou pare/reinicie um
  serviço trocando a chave privada) para mostrar que o consumidor **descarta**
  o evento quando a assinatura não bate.
- Ajuste `PROBABILIDADE_APROVACAO` em `PagamentoApp` e o intervalo de
  publicação em `PromocoesApp` (`Thread.sleep(5000)`) conforme a necessidade
  da demonstração.
