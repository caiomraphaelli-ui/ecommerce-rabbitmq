# Sistema Distribuído de E-commerce — Microsserviços + RabbitMQ + REST + SSE

Trabalho 2 de Sistemas Distribuídos (UTFPR). Evolui o e-commerce orientado a
eventos do Trabalho 1 para uma aplicação Web completa:

- **Frontend web** em HTML + CSS + JavaScript puro (o backend é Java), que fala com o backend **só via REST** e recebe notificações em tempo real via **SSE**;
- **MS Principal / API Gateway**: expõe a API REST, mantém as conexões SSE e traduz as ações do usuário em eventos no **RabbitMQ**;
- **MS Estoque, Pagamento, Entrega e Promoções** conversando entre si **somente** por eventos assinados no RabbitMQ;
- **Mock de Pagamento** (sistema externo): recebe a cobrança via REST, abre o checkout em nova aba e devolve o resultado por **Webhook**;
- **API externa de e-mail (Resend)** para enviar as promoções aos inscritos;
- **Assinatura digital RSA** em todas as mensagens trafegadas no RabbitMQ, como no Trab1.

## 1. Arquitetura

```
                    ┌───────────────────────────────┐
                    │  Frontend web (HTML/CSS/JS)   │
                    └──────────┬──────────▲─────────┘
                          REST │          │ SSE
                    ┌──────────▼──────────┴─────────┐
                    │ MS Principal / API Gateway    │ :8080 ── REST ──► MS Estoque :8081/produtos
                    └──────────────┬────────────────┘
                                   │ eventos assinados (RSA)
    ┌──────────────────────────────▼──────────────────────────────────┐
    │  RabbitMQ — exchange "eCommerce" (direct) + "Promoções" (topic)   │
    └──────┬───────────────┬────────────────┬────────────────┬────────┘
     MS Estoque      MS Pagamento :8082   MS Entrega     MS Promoções ── REST ──► API Resend
     (data/estoque.json)  │ ▲ Webhook                    (data/interessados.json)
                     REST │ │                                 │ promocao.categoria.X
                    ┌─────▼─┴──────────────┐            Consumidores C1 / C2 (Trab1)
                    │ Mock de Pagamento    │ :8090
                    │ (checkout em nova aba)│
                    └──────────────────────┘
```

### Eventos (exchange `eCommerce`, direct)

| Routing key | Publicado por | Consumido por |
|---|---|---|
| `pedido.criado` | Principal | Estoque |
| `pedido.excluido` | Principal | Estoque (devolve a reserva), Pagamento (cancela a cobrança pendente) |
| `interesse.promocao` | Principal | Promoções |
| `pedido.estoque_ok` | Estoque | Principal, Pagamento |
| `estoque.indisponivel` | Estoque | Principal |
| `pagamento.pendente` | Pagamento | Principal (repassa a URL do checkout ao frontend via SSE) |
| `pagamento.aprovado` | Pagamento | Principal, Entrega |
| `pagamento.recusado` | Pagamento | Principal |
| `pedido.enviado` | Entrega | Principal |

Exchange `Promoções` (topic): `promocao.categoria.A|B|C`, publicada pelo MS
Promoções e consumida por C1 (bind A e B) e C2 (bind `promocao.categoria.*`), como no Trab1.

Filas: `fila.principal`, `fila.estoque`, `fila.pagamento`, `fila.entrega`,
`fila.promocoes`, `fila.c1`, `fila.c2`. Cada consumidor tem sua própria fila.

> **Por que existe `pagamento.pendente`?** A URL do checkout só existe depois que o
> estoque é confirmado e o MS Pagamento cria a cobrança no Mock. Na Figura 1 do
> enunciado, o MS Pagamento só se liga ao RabbitMQ (e ao Mock), sem nenhuma ligação
> direta com o Gateway. Por isso a URL viaja como evento assinado
> `Pagamento → RabbitMQ → Gateway → SSE → Frontend`.

### Fluxo de um pedido

1. O frontend faz `POST /api/pedidos`. O Gateway publica `pedido.criado` e responde `201`.
2. **Estoque** reserva os itens (persistindo em `data/estoque.json`) e publica `pedido.estoque_ok`,
   ou publica `estoque.indisponivel`. Nesse caso o Gateway publica `pedido.excluido`.
3. **Pagamento** recebe `pedido.estoque_ok` e faz `POST {mock}/api/cobrancas` com
   `{pedidoId, valor, webhookUrl}`. O Mock devolve `checkoutUrl` e o Pagamento publica `pagamento.pendente`.
4. O Gateway envia a URL por **SSE** e o frontend leva a aba (aberta no clique de
   "Finalizar pedido", para não cair no bloqueador de pop-up) até o checkout do Mock.
5. O usuário clica em **Pagamento Aprovado** ou **Pagamento Recusado**. O Mock faz
   `POST http://localhost:8082/webhook/pagamento` com `{cobrancaId, pedidoId, status}`.
6. **Pagamento** publica `pagamento.aprovado` ou `pagamento.recusado`. Na recusa, o Gateway publica `pedido.excluido`
   e o Estoque devolve os itens.
7. **Entrega** recebe `pagamento.aprovado`, emite a nota fiscal (simulada, ~3 s) e publica `pedido.enviado`.
8. Cada mudança de status chega ao navegador do dono do pedido por SSE: Estoque Confirmado,
   Aguardando Pagamento, Pagamento Aprovado/Recusado e Enviado.

O cliente pode cancelar o pedido até o pagamento ser aprovado (`DELETE /api/pedidos/{id}`).
O Gateway publica `pedido.excluido`, o Estoque devolve a reserva e o Pagamento cancela a
cobrança. Se o usuário ainda tentar pagar no Mock, o Webhook responde `409` e o checkout mostra "cobrança cancelada".

## 2. API REST do Gateway (`http://localhost:8080`)

| Método e caminho | Descrição |
|---|---|
| `GET /api/produtos` | Lista os produtos disponíveis em estoque (o Gateway consulta o MS Estoque via REST) |
| `POST /api/pedidos` | Cria pedido. Corpo: `{"clienteId":"...","itens":[{"produtoId":1,"quantidade":2}]}` |
| `GET /api/pedidos?clienteId=...` | Pedidos do cliente, com status e histórico |
| `DELETE /api/pedidos/{id}?clienteId=...` | Cancela o pedido (publica `pedido.excluido`) |
| `POST /api/promocoes/interesse` | Registra interesse. Corpo: `{"email":"x@y.com","categorias":["A","C"]}` (publica `interesse.promocao`) |
| `DELETE /api/promocoes/interesse?email=...` | Cancela o interesse (publica `interesse.promocao` com `acao=CANCELAR`) |
| `GET /api/eventos?clienteId=...` | Stream **SSE** (`text/event-stream`) com eventos `pedido` |
| `GET /` | Arquivos do frontend (`frontend/`) |

Outros endpoints HTTP do sistema:

- **MS Estoque**: `GET http://localhost:8081/produtos[?disponiveis=true]`
- **MS Pagamento**: `POST http://localhost:8082/webhook/pagamento` (Webhook)
- **Mock de Pagamento**: `POST /api/cobrancas`, `GET /api/cobrancas/{id}`, `POST /api/cobrancas/{id}/cancelar`,
  `GET /checkout/{id}` (página), `POST /checkout/{id}/aprovar|recusar`

O frontend gera um `clienteId` (guardado no `localStorage`) que identifica o navegador.
Os pedidos e as notificações SSE são separados por cliente: duas abas do mesmo
navegador recebem as mesmas notificações, e outro navegador não vê esses pedidos.

### Exemplo de mensagem SSE

```
event: pedido
data: {"tipo":"PAGAMENTO_APROVADO","mensagem":"Pedido 1a2b3c4d: Pagamento aprovado","pedido":{"id":"1a2b3c4d","status":"PAGAMENTO_APROVADO",...}}
```

O Gateway manda `retry: 3000` (o navegador reconecta sozinho em 3 s) e um comentário
`: ping` a cada 15 s para manter a conexão viva e detectar abas fechadas. Ao reconectar,
o frontend busca `GET /api/pedidos` para recuperar o que perdeu.

## 3. Criptografia assimétrica (RSA 2048 bits)

Igual ao Trab1 e aplicada a **todas** as mensagens do RabbitMQ, inclusive as novas
`interesse.promocao` e `pagamento.pendente`:

- cada microsserviço tem a pasta `keys/<nome>/` com sua `private_key.pem` e as chaves públicas dos demais em `public_keys/`;
- o produtor gera o hash SHA-256 do envelope (`eventId`, `exchange`, `routingKey`, `producer`, `timestamp`, `payload`), assina com a chave privada e coloca o resultado no campo `Signature`;
- o consumidor obtém a chave pública do produtor, confere se ele é o responsável por aquela routing key e verifica a assinatura. **Só processa o evento se ela for válida**; caso contrário, descarta.

O Mock de Pagamento e o Resend são sistemas externos e não usam o RabbitMQ.

Veja `CryptoUtils`, `Envelope`, `KeyStoreManager`, `EventBus` e `RabbitConfig.produtorAutorizado`.

## 4. Estrutura do projeto

```
ecommerce-rabbitmq/
├── frontend/                    -> index.html, css/styles.css, js/api.js (REST + SSE), js/app.js (interface)
├── src/main/java/com/ecommerce/
│   ├── common/                  -> base do Trab1 (ProcessoMensageria, EventBus, CryptoUtils...) +
│   │                               Http (servidor/cliente HTTP do JDK), HttpConfig (portas/URLs),
│   │                               ArquivoJson (persistência), Ambiente (.env)
│   ├── principal/               -> API Gateway: PrincipalApp (REST), SseHub (SSE), Pedido, PedidoStatus
│   ├── estoque/                 -> MS Estoque (persistência + GET /produtos)
│   ├── pagamento/               -> MS Pagamento (cria cobrança + Webhook)
│   ├── entrega/                 -> MS Entrega
│   ├── promocoes/               -> MS Promoções + ResendEmail (API de e-mail) + EmailTemplates
│   ├── mockpagamento/           -> Mock de Pagamento (sistema externo)
│   ├── consumidores/            -> Consumidores C1/C2 (Trab1)
│   └── keys/KeyGeneratorTool    -> gera os pares de chave RSA
├── src/main/resources/mock-pagamento/checkout.html   -> página de checkout do Mock
├── keys/                        -> chaves RSA por microsserviço
├── data/                        -> criado em execução: estoque.json, interessados.json
└── scripts/                     -> build, execução individual e iniciar-tudo (.sh e .ps1)
```

Os servidores HTTP usam o `com.sun.net.httpserver.HttpServer`, que já vem no JDK. Não há framework web
e as únicas dependências continuam sendo `amqp-client` e `gson`.

## 5. Pré-requisitos

- **Java 11+** e **Maven 3.6+**
- **RabbitMQ**. Pelo Docker:

  ```bash
  docker run -d --hostname rabbit-ecommerce --name rabbit-ecommerce \
    -p 5672:5672 -p 15672:15672 rabbitmq:3-management
  ```

- (Opcional, para enviar e-mails de verdade) conta grátis no **[Resend](https://resend.com)**

### Configuração (`.env`)

Copie `.env.example` para `.env` na raiz do projeto:

```bash
RESEND_API_KEY=re_xxxxxxxx                     # Resend -> API Keys -> Create API Key
RESEND_FROM=BC E-commerce <onboarding@resend.dev>   # remetente de testes do Resend
RABBIT_PORT=5672                               # porta AMQP do RabbitMQ no host
```

O `.env` é lido pelo próprio Java (classe `Ambiente`), então vale para os scripts, para a IDE e
para o terminal. Variáveis de ambiente de mesmo nome têm prioridade. **Não versione o `.env`**:
ele já está no `.gitignore`.

Sem `RESEND_API_KEY`, o MS Promoções só **simula** o envio no log, e o resto do sistema
funciona normalmente.

> **Limitação do plano grátis do Resend:** sem um domínio próprio verificado, o remetente
> `onboarding@resend.dev` só entrega para o **e-mail dono da conta Resend**. Na defesa,
> cadastrem no frontend o mesmo e-mail usado para criar a conta (ou verifiquem um domínio
> no Resend para enviar a qualquer destinatário).

## 6. Como rodar

```bash
cd ecommerce-rabbitmq
./scripts/iniciar-tudo.sh                                            # Linux/Mac
powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1    # Windows
```

O script confere o RabbitMQ, compila (se preciso), gera as chaves (se preciso), sobe os
8 processos e abre o frontend em **http://localhost:8080**. No Linux/Mac os processos rodam
em segundo plano com logs em `logs/<processo>.log`, e **Ctrl+C** encerra tudo. No Windows cada
processo abre em uma janela própria, e **ENTER** no terminal encerra tudo.

Para depurar: `./scripts/iniciar-tudo-debug.sh` (ou `iniciar-tudo-debug.ps1`). Cada processo
abre uma porta JDWP de 5005 a 5012, e há configurações "Attach" em `.vscode/launch.json`.

Também dá para subir cada processo em um terminal (`./scripts/run-estoque.sh`, `run-pagamento.sh`,
`run-entrega.sh`, `run-mock-pagamento.sh`, `run-consumidor-C1.sh`, `run-consumidor-C2.sh`,
`run-promocoes.sh`, `run-principal.sh`).

| Endereço | O quê |
|---|---|
| http://localhost:8080 | Frontend + API Gateway |
| http://localhost:8081/produtos | REST do MS Estoque |
| http://localhost:8082/webhook/pagamento | Webhook do MS Pagamento |
| http://localhost:8090 | Mock de Pagamento |
| http://localhost:15672 | Painel do RabbitMQ (guest/guest) |

Ajustes por system property (`java -D...`): `gateway.porta`, `estoque.porta`, `pagamento.porta`,
`mock.porta`, `estoque.url`, `mock.url`, `pagamento.webhook.url`,
`promocoes.intervalo.ms` (padrão 60000: uma promoção por minuto, para não estourar a cota do
Resend) e `entrega.tempo.ms` (padrão 3000).

Para **zerar o estoque e os inscritos**, pare o sistema e apague a pasta `data/`. O estoque
volta a 10 unidades por produto.

## 7. Mapeamento com o enunciado (Trab2)

| Requisito | Onde está |
|---|---|
| **Frontend (0,5)** em linguagem diferente do backend, via REST | `frontend/` (HTML/CSS/JS), chamadas em `js/api.js` |
| Catálogo e pedidos via REST | abas Catálogo + carrinho, `GET /api/produtos`, `POST /api/pedidos` |
| Recebe a URL do Mock e abre nova aba | `app.js`: `abrirAbaDeEspera` / `atualizarAbaPagamento`, botão "Pagar agora" |
| Acompanhamento por SSE | `api.conectarEventos` (EventSource), linha do tempo + feed "Notificações em tempo real" |
| Inscrição em promoções (e-mail + categorias) | aba Promoções |
| **Gateway (1,8)**: publica `interesse.promocao`, `pedido.criado`, `pedido.excluido` (0,1) | `PrincipalApp.rotaInteresse`, `criarPedido`, `cancelarPedido`, `aoFaltarEstoque`, `aoRecusarPagamento` |
| Consome os eventos de Estoque, Pagamento e Entrega | `PrincipalApp.iniciar()` (tratadores `aoConfirmarEstoque`, `aoFaltarEstoque`, `aoAprovarPagamento`, ...) |
| SSE com toda mudança de estado do pedido (0,5) | `SseHub` + `PrincipalApp.mudarStatus/notificar` |
| Endpoint listar produtos disponíveis (0,1) | `GET /api/produtos` |
| Endpoint criar pedidos (0,1) | `POST /api/pedidos` |
| Endpoint registrar interesse (0,1) | `POST /api/promocoes/interesse` |
| Endpoint cancelar interesse (0,1) | `DELETE /api/promocoes/interesse?email=` |
| Gateway consulta produtos no MS Estoque via REST | `PrincipalApp.consultarEstoque` → `GET :8081/produtos` |
| **Estoque (0,2)**: consome `pedido.criado`/`pedido.excluido`, publica `estoque_ok`/`indisponivel` | `EstoqueApp.tratarPedidoCriado/tratarPedidoExcluido` |
| Persiste os dados dos produtos (0,1) | `data/estoque.json` via `ArquivoJson` (produtos + reservas) |
| Endpoint para o Gateway consultar produtos (0,1) | `EstoqueApp.iniciarServidorRest` |
| **Pagamento (0,2)**: cria cobrança no Mock informando o Webhook (0,1) | `PagamentoApp.criarCobranca` |
| Endpoint do Webhook APROVADO/RECUSADO (0,1) | `PagamentoApp.iniciarServidorWebhook` / `processarWebhook` |
| Publica `pagamento.aprovado`/`pagamento.recusado` | `PagamentoApp.processarWebhook` |
| **Entrega**: consome `pagamento.aprovado`, emite NF, publica `pedido.enviado` | `EntregaApp` |
| **Promoções (0,4)**: consome `interesse.promocao` (0,1) | `PromocoesApp.registrarInteresse` (persiste em `data/interessados.json`) |
| Gera promoções e notifica os interessados por e-mail via API externa (0,3) | `PromocoesApp.gerarEPublicarPromocao/notificarInteressados` + `ResendEmail` |
| **Mock de Pagamento (0,2)**: processa a cobrança via REST (0,1) | `MockPagamentoApp.criarCobranca` (`POST /api/cobrancas`) |
| Página com "Pagamento Aprovado"/"Pagamento Recusado" que dispara o Webhook (0,1) | `checkout.html` + `MockPagamentoApp.decidir` |
| Assinatura digital em todas as mensagens do RabbitMQ | `EventBus.publicar/receber` (inalterado do Trab1) |

## 8. Roteiro sugerido para a defesa

1. Rode `iniciar-tudo` e mostre no painel do RabbitMQ as exchanges, as filas e os bindings (incluindo `fila.promocoes` ← `interesse.promocao`).
2. **Caminho feliz:** adicione itens ao carrinho e clique em Finalizar. A aba de pagamento abre sozinha. Clique em "Pagamento Aprovado" e volte à loja: o pedido passa por todas as etapas e o feed mostra cada evento SSE chegando.
3. **Recusa:** repita e clique em "Pagamento Recusado". O pedido é cancelado e o estoque volta (o catálogo se atualiza).
4. **Sem estoque:** peça mais unidades do que existem (o carrinho avisa). O Estoque publica `estoque.indisponivel`.
5. **Cancelamento:** cancele um pedido aguardando pagamento e tente pagar na aba do Mock. A cobrança aparece como cancelada.
6. **Persistência:** reinicie o MS Estoque e mostre que as quantidades continuam as mesmas (`data/estoque.json`).
7. **Promoções:** cadastre o e-mail da conta Resend em uma ou mais categorias e mostre o e-mail de confirmação. Para não esperar 1 minuto, rode o Promoções com `-Dpromocoes.intervalo.ms=10000`. O log do MS Promoções mostra o id devolvido pelo Resend.
8. **SSE puro:** `curl -N "localhost:8080/api/eventos?clienteId=<id do localStorage>"` mostra o stream bruto.
9. **Assinatura:** publique um evento forjado pelo painel do RabbitMQ (ex.: `pedido.criado` com `Signature` inválida) e mostre o Estoque descartando.
