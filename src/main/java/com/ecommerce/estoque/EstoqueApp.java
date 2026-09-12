package com.ecommerce.estoque;

import com.ecommerce.common.*;
import com.rabbitmq.client.BuiltinExchangeType;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Microsserviço Estoque.
 *
 * Consome pedido.criado e pedido.excluido. Ao receber pedido.criado, verifica
 * a disponibilidade dos produtos; se disponíveis, reserva/baixa do estoque e
 * publica pedido.estoque_ok; caso contrário publica estoque.indisponivel.
 * Ao receber pedido.excluido, devolve ao estoque os produtos que haviam sido
 * reservados para aquele pedido (se houver reserva).
 */
public class EstoqueApp extends ProcessoMensageria {

    private final Map<Integer, Integer> estoque = new ConcurrentHashMap<>();
    private final Map<String, List<ItemPedido>> reservas = new ConcurrentHashMap<>();

    public EstoqueApp() throws Exception {
        super("Estoque", RabbitConfig.MS_ESTOQUE, true);
        for (Produto p : Catalogo.listar()) {
            estoque.put(p.id, Catalogo.estoqueInicialPadrao());
        }
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_ESTOQUE,
                RabbitConfig.RK_PEDIDO_CRIADO,
                RabbitConfig.RK_PEDIDO_EXCLUIDO);

        registrarTratador(RabbitConfig.RK_PEDIDO_CRIADO, Payloads.PedidoCriado.class, this::tratarPedidoCriado);
        registrarTratador(RabbitConfig.RK_PEDIDO_EXCLUIDO, Payloads.PedidoExcluido.class, this::tratarPedidoExcluido);

        System.out.println("Microsserviço Estoque iniciado. Estoque inicial: " + estoque);
        consumir(RabbitConfig.FILA_ESTOQUE);
    }

    private synchronized void tratarPedidoCriado(Payloads.PedidoCriado pedido) throws IOException {
        log("Recebido pedido.criado " + pedido.pedidoId);

        boolean disponivel = true;
        for (ItemPedido item : pedido.itens) {
            Integer disponivelQtd = estoque.get(item.produtoId);
            if (disponivelQtd == null || disponivelQtd < item.quantidade) {
                disponivel = false;
                break;
            }
        }

        if (disponivel) {
            for (ItemPedido item : pedido.itens) {
                estoque.merge(item.produtoId, -item.quantidade, Integer::sum);
            }
            reservas.put(pedido.pedidoId, pedido.itens);
            double valorTotal = calcularValorTotal(pedido.itens);
            log("Pedido " + pedido.pedidoId + ": estoque reservado. Valor total: R$ " + String.format("%.2f", valorTotal));
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_PEDIDO_ESTOQUE_OK,
                    new Payloads.PedidoEstoqueOk(pedido.pedidoId, valorTotal, pedido.itens));
        } else {
            log("Pedido " + pedido.pedidoId + ": produto(s) indisponível(is).");
            publicar(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.RK_ESTOQUE_INDISPONIVEL,
                    new Payloads.EstoqueIndisponivel(pedido.pedidoId, "Quantidade solicitada indisponível"));
        }
    }

    private synchronized void tratarPedidoExcluido(Payloads.PedidoExcluido evento) {
        String pedidoId = evento.pedidoId;
        List<ItemPedido> itensReservados = reservas.remove(pedidoId);
        if (itensReservados != null) {
            for (ItemPedido item : itensReservados) {
                estoque.merge(item.produtoId, item.quantidade, Integer::sum);
            }
            log("Pedido " + pedidoId + " excluído: itens devolvidos ao estoque.");
        } else {
            log("Pedido " + pedidoId + " excluído: nenhuma reserva encontrada (nada a devolver).");
        }
    }

    private double calcularValorTotal(List<ItemPedido> itens) {
        double total = 0.0;
        for (ItemPedido item : itens) {
            total += Catalogo.porId(item.produtoId).map(p -> p.preco).orElse(0.0) * item.quantidade;
        }
        return total;
    }

    public static void main(String[] args) throws Exception {
        new EstoqueApp().iniciar();
        Thread.currentThread().join();
    }
}
