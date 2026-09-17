package com.ecommerce.principal;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PrincipalApp extends ProcessoMensageria {

    private static final long TIMEOUT_CONSULTA_ESTOQUE_MS = 1500;
    private static final long TIMEOUT_CANCELAMENTO_PENDENTE_MS = 1000;

    private final Map<String, Pedido> pedidos = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> estoqueVisivel = new ConcurrentHashMap<>();
    private volatile CountDownLatch aguardandoEstoque;

    // Conta cancelamentos automáticos (estoque indisponível / pagamento recusado) que
    // a thread do RabbitMQ já decidiu fazer mas ainda não terminou de publicar. A thread
    // do menu espera esse contador zerar antes de perguntar o estoque, senão a pergunta
    // pode sair (e voltar) antes do pedido.excluido correspondente — mostrando um número
    // que ainda não considera a devolução que está em andamento.
    private final AtomicInteger cancelamentosPendentes = new AtomicInteger(0);

    public PrincipalApp() throws Exception {
        super("Principal", RabbitConfig.MS_PRINCIPAL, true);
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_PRINCIPAL,
                RabbitConfig.RK_PAGAMENTO_APROVADO,
                RabbitConfig.RK_PAGAMENTO_RECUSADO,
                RabbitConfig.RK_PEDIDO_ENVIADO,
                RabbitConfig.RK_PEDIDO_ESTOQUE_OK,
                RabbitConfig.RK_ESTOQUE_INDISPONIVEL,
                RabbitConfig.RK_ESTOQUE_ATUALIZADO);

        registrarTratador(RabbitConfig.RK_PEDIDO_ESTOQUE_OK, Payloads.PedidoEstoqueOk.class, this::aoConfirmarEstoque);
        registrarTratador(RabbitConfig.RK_ESTOQUE_INDISPONIVEL, Payloads.EstoqueIndisponivel.class, this::aoFaltarEstoque);
        registrarTratador(RabbitConfig.RK_ESTOQUE_ATUALIZADO, Payloads.EstoqueAtualizado.class, this::aoAtualizarEstoque);
        registrarTratador(RabbitConfig.RK_PAGAMENTO_APROVADO, Payloads.PagamentoAprovado.class, this::aoAprovarPagamento);
        registrarTratador(RabbitConfig.RK_PAGAMENTO_RECUSADO, Payloads.PagamentoRecusado.class, this::aoRecusarPagamento);
        registrarTratador(RabbitConfig.RK_PEDIDO_ENVIADO, Payloads.PedidoEnviado.class, this::aoEnviarPedido);

        consumir(RabbitConfig.FILA_PRINCIPAL);

        // a fila e o bind pra estoque.atualizado já existem acima, então a resposta não se perde
        publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_ESTOQUE_CONSULTAR, new Payloads.EstoqueConsultar());
    }

    private void aoConfirmarEstoque(Payloads.PedidoEstoqueOk evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.status = PedidoStatus.AGUARDANDO_PAGAMENTO;
        p.valorTotal = evento.valorTotal;
        avisar("Pedido " + p.id + ": estoque confirmado, aguardando pagamento (R$ "
                + String.format("%.2f", p.valorTotal) + ").");
    }

    private void aoFaltarEstoque(Payloads.EstoqueIndisponivel evento) throws IOException {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.status = PedidoStatus.CANCELADO_ESTOQUE;
        avisar("Pedido " + p.id + ": produto indisponível em estoque. Cancelando pedido...");
        cancelarAutomaticamente(p.id, "Produto indisponível em estoque");
    }

    private void aoAprovarPagamento(Payloads.PagamentoAprovado evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.status = PedidoStatus.PAGAMENTO_APROVADO;
        avisar("Pedido " + p.id + ": pagamento aprovado!");
    }

    private void aoRecusarPagamento(Payloads.PagamentoRecusado evento) throws IOException {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.status = PedidoStatus.CANCELADO_PAGAMENTO;
        avisar("Pedido " + p.id + ": pagamento recusado. Cancelando pedido...");
        cancelarAutomaticamente(p.id, "Pagamento recusado");
    }

    private void aoEnviarPedido(Payloads.PedidoEnviado evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.status = PedidoStatus.ENVIADO;
        avisar("Pedido " + p.id + ": enviado! Nota fiscal: " + evento.numeroNota);
    }

    /** Atualiza o cache local de estoque exibido no menu; não gera notificação (não é status de pedido). */
    private void aoAtualizarEstoque(Payloads.EstoqueAtualizado evento) {
        estoqueVisivel.clear();
        estoqueVisivel.putAll(evento.disponibilidade);
        CountDownLatch latch = aguardandoEstoque;
        if (latch != null) {
            latch.countDown();
        }
    }

    /** Mostra a notificação sem atrapalhar muito o menu que está esperando entrada do usuário. */
    private void avisar(String mensagem) {
        System.out.println("\n[Principal] " + mensagem);
        System.out.print("\n> ");
    }

    private void publicarPedidoExcluido(String pedidoId, String motivo) throws IOException {
        publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PEDIDO_EXCLUIDO,
                new Payloads.PedidoExcluido(pedidoId, motivo));
    }

    /** Igual a publicarPedidoExcluido, mas marcado como pendente até a publicação terminar (ver cancelamentosPendentes). */
    private void cancelarAutomaticamente(String pedidoId, String motivo) throws IOException {
        cancelamentosPendentes.incrementAndGet();
        try {
            publicarPedidoExcluido(pedidoId, motivo);
        } finally {
            cancelamentosPendentes.decrementAndGet();
        }
    }

    public void executarMenu() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\n===== E-COMMERCE - MENU PRINCIPAL =====");
            System.out.println("1. Visualizar produtos");
            System.out.println("2. Realizar pedido");
            System.out.println("3. Excluir pedido");
            System.out.println("4. Consultar meus pedidos");
            System.out.println("0. Sair");
            System.out.print("Opção: ");
            String opcao = scanner.nextLine().trim();

            try {
                switch (opcao) {
                    case "1":
                        visualizarProdutos();
                        break;
                    case "2":
                        realizarPedido(scanner);
                        break;
                    case "3":
                        excluirPedido(scanner);
                        break;
                    case "4":
                        consultarPedidos();
                        break;
                    case "0":
                        System.out.println("Encerrando...");
                        return;
                    default:
                        System.out.println("Opção inválida.");
                }
            } catch (Exception e) {
                System.out.println("Erro: " + e.getMessage());
            }
        }
    }

    private void visualizarProdutos() {
        atualizarEstoqueVisivel();
        System.out.println("\n--- Catálogo de produtos ---");
        for (Produto p : Catalogo.listar()) {
            Integer disponivel = estoqueVisivel.get(p.id);
            String estoqueTxt = disponivel == null ? "estoque: consultando..." : "estoque: " + disponivel + " un.";
            System.out.println(p + "  [" + estoqueTxt + "]");
        }
    }

    /**
     * Pede um snapshot fresco do estoque e espera a resposta chegar (até
     * TIMEOUT_CONSULTA_ESTOQUE_MS) antes de exibir o catálogo. Evita mostrar
     * um número desatualizado logo depois de um pedido ser cancelado (o
     * estoque só é devolvido quando o Estoque processa o pedido.excluido,
     * e isso é assíncrono).
     */
    private void atualizarEstoqueVisivel() {
        aguardarCancelamentosPendentes();
        CountDownLatch latch = new CountDownLatch(1);
        aguardandoEstoque = latch;
        try {
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_ESTOQUE_CONSULTAR, new Payloads.EstoqueConsultar());
            latch.await(TIMEOUT_CONSULTA_ESTOQUE_MS, TimeUnit.MILLISECONDS);
        } catch (IOException | InterruptedException e) {
            // segue com o que já estiver em estoqueVisivel (ou "consultando..." se ainda vazio)
        } finally {
            aguardandoEstoque = null;
        }
    }

    private void aguardarCancelamentosPendentes() {
        long limite = System.currentTimeMillis() + TIMEOUT_CANCELAMENTO_PENDENTE_MS;
        while (cancelamentosPendentes.get() > 0 && System.currentTimeMillis() < limite) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void realizarPedido(Scanner scanner) throws IOException {
        List<ItemPedido> itens = new ArrayList<>();
        visualizarProdutos();
        System.out.println("Informe os itens do pedido (produtoId quantidade). Linha vazia para finalizar.");
        while (true) {
            System.out.print("Item (ex: '1 2') ou ENTER para finalizar: ");
            String linha = scanner.nextLine().trim();
            if (linha.isEmpty()) break;
            String[] partes = linha.split("\\s+");
            if (partes.length != 2) {
                System.out.println("Formato inválido.");
                continue;
            }
            try {
                int produtoId = Integer.parseInt(partes[0]);
                int quantidade = Integer.parseInt(partes[1]);
                if (!Catalogo.porId(produtoId).isPresent() || quantidade <= 0) {
                    System.out.println("Produto inválido ou quantidade inválida.");
                    continue;
                }
                itens.add(new ItemPedido(produtoId, quantidade));
            } catch (NumberFormatException e) {
                System.out.println("Formato inválido.");
            }
        }
        if (itens.isEmpty()) {
            System.out.println("Pedido cancelado (nenhum item informado).");
            return;
        }

        String pedidoId = UUID.randomUUID().toString().substring(0, 8);
        Pedido pedido = new Pedido(pedidoId, itens);
        pedidos.put(pedidoId, pedido);

        publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PEDIDO_CRIADO,
                new Payloads.PedidoCriado(pedidoId, itens));
        System.out.println("Pedido " + pedidoId + " criado e publicado. Acompanhe o status em 'Consultar meus pedidos'.");
    }

    private void excluirPedido(Scanner scanner) throws IOException {
        consultarPedidos();
        System.out.print("Informe o id do pedido a excluir: ");
        String id = scanner.nextLine().trim();
        Pedido p = pedidos.get(id);
        if (p == null) {
            System.out.println("Pedido não encontrado.");
            return;
        }
        if (p.status == PedidoStatus.ENVIADO) {
            System.out.println("Não é possível excluir um pedido já enviado.");
            return;
        }
        p.status = PedidoStatus.EXCLUIDO_PELO_USUARIO;
        publicarPedidoExcluido(id, "Cancelado pelo usuário");
        System.out.println("Solicitação de exclusão do pedido " + id + " enviada.");
    }

    private void consultarPedidos() {
        if (pedidos.isEmpty()) {
            System.out.println("Nenhum pedido realizado ainda.");
            return;
        }
        System.out.println("\n--- Meus pedidos ---");
        for (Pedido p : pedidos.values()) {
            System.out.printf("Pedido %s | status: %s | valor: R$ %.2f%n", p.id, p.status.descricao, p.valorTotal);
        }
    }

    public static void main(String[] args) throws Exception {
        PrincipalApp app = new PrincipalApp();
        app.iniciar();
        System.out.println("Microsserviço Principal iniciado. Aguardando eventos e comandos do usuário.");
        app.executarMenu();
        System.exit(0);
    }
}
