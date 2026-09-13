package com.ecommerce.principal;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Microsserviço Principal.
 *
 * Responsável pela interação com o usuário via terminal (visualizar produtos,
 * realizar pedidos, excluir pedidos, consultar pedidos/status) e por publicar
 * o evento pedido.criado. Consome os eventos que afetam o status dos pedidos
 * e publica pedido.excluido quando o estoque está indisponível ou o pagamento
 * é recusado.
 */
public class PrincipalApp extends ProcessoMensageria {

    private final Map<String, Pedido> pedidos = new ConcurrentHashMap<>();

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
                RabbitConfig.RK_ESTOQUE_INDISPONIVEL);

        registrarTratador(RabbitConfig.RK_PEDIDO_ESTOQUE_OK, Payloads.PedidoEstoqueOk.class, this::aoConfirmarEstoque);
        registrarTratador(RabbitConfig.RK_ESTOQUE_INDISPONIVEL, Payloads.EstoqueIndisponivel.class, this::aoFaltarEstoque);
        registrarTratador(RabbitConfig.RK_PAGAMENTO_APROVADO, Payloads.PagamentoAprovado.class, this::aoAprovarPagamento);
        registrarTratador(RabbitConfig.RK_PAGAMENTO_RECUSADO, Payloads.PagamentoRecusado.class, this::aoRecusarPagamento);
        registrarTratador(RabbitConfig.RK_PEDIDO_ENVIADO, Payloads.PedidoEnviado.class, this::aoEnviarPedido);

        consumir(RabbitConfig.FILA_PRINCIPAL);
    }

    // -------------------- Tratadores dos eventos consumidos --------------------

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
        publicarPedidoExcluido(p.id, "Produto indisponível em estoque");
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
        publicarPedidoExcluido(p.id, "Pagamento recusado");
    }

    private void aoEnviarPedido(Payloads.PedidoEnviado evento) {
        Pedido p = pedidos.get(evento.pedidoId);
        if (p == null) {
            return;
        }
        p.status = PedidoStatus.ENVIADO;
        avisar("Pedido " + p.id + ": enviado! Nota fiscal: " + evento.numeroNota);
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

    // -------------------- Menu / interação com o usuário --------------------

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
        System.out.println("\n--- Catálogo de produtos ---");
        for (Produto p : Catalogo.listar()) {
            System.out.println(p);
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
