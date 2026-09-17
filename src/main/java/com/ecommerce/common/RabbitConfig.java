package com.ecommerce.common;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public final class RabbitConfig {

    private RabbitConfig() {
    }

    public static final String EXCHANGE_ECOMMERCE = "eCommerce";
    public static final String EXCHANGE_PROMOCOES = "Promoções";

    public static final String FILA_PRINCIPAL = "fila.principal";
    public static final String FILA_ESTOQUE = "fila.estoque";
    public static final String FILA_PAGAMENTO = "fila.pagamento";
    public static final String FILA_ENTREGA = "fila.entrega";
    public static final String FILA_C1 = "fila.c1";
    public static final String FILA_C2 = "fila.c2";

    public static final String RK_PEDIDO_CRIADO = "pedido.criado";
    public static final String RK_PEDIDO_EXCLUIDO = "pedido.excluido";
    public static final String RK_PEDIDO_ESTOQUE_OK = "pedido.estoque_ok";
    public static final String RK_ESTOQUE_INDISPONIVEL = "estoque.indisponivel";
    public static final String RK_ESTOQUE_ATUALIZADO = "estoque.atualizado";
    public static final String RK_ESTOQUE_CONSULTAR = "estoque.consultar";
    public static final String RK_PAGAMENTO_APROVADO = "pagamento.aprovado";
    public static final String RK_PAGAMENTO_RECUSADO = "pagamento.recusado";
    public static final String RK_PEDIDO_ENVIADO = "pedido.enviado";

    private static final String PREFIXO_PROMOCAO = "promocao.categoria.";
    public static String rkPromocaoCategoria(String categoria) {
        return PREFIXO_PROMOCAO + categoria;
    }
    public static final String BK_PROMOCAO_C2_TODAS = "promocao.categoria.*";

    public static final String MS_PRINCIPAL = "principal";
    public static final String MS_ESTOQUE = "estoque";
    public static final String MS_PAGAMENTO = "pagamento";
    public static final String MS_ENTREGA = "entrega";
    public static final String MS_PROMOCOES = "promocoes";

    public static final String[] MICROSSERVICOS = {MS_PRINCIPAL, MS_ESTOQUE, MS_PAGAMENTO, MS_ENTREGA, MS_PROMOCOES};

    public static final String CONSUMIDOR_C1 = "consumidor-c1";
    public static final String CONSUMIDOR_C2 = "consumidor-c2";

    /**
     * Microsserviço responsável por publicar cada routing key. Usado na
     * validação: um evento só é aceito se foi assinado pelo seu produtor.
     * Retorna null para routing keys desconhecidas.
     */
    public static String produtorAutorizado(String routingKey) {
        if (routingKey == null) {
            return null;
        }
        switch (routingKey) {
            case RK_PEDIDO_CRIADO:
            case RK_PEDIDO_EXCLUIDO:
            case RK_ESTOQUE_CONSULTAR:
                return MS_PRINCIPAL;
            case RK_PEDIDO_ESTOQUE_OK:
            case RK_ESTOQUE_INDISPONIVEL:
            case RK_ESTOQUE_ATUALIZADO:
                return MS_ESTOQUE;
            case RK_PAGAMENTO_APROVADO:
            case RK_PAGAMENTO_RECUSADO:
                return MS_PAGAMENTO;
            case RK_PEDIDO_ENVIADO:
                return MS_ENTREGA;
            default:
                return routingKey.startsWith(PREFIXO_PROMOCAO) ? MS_PROMOCOES : null;
        }
    }

    public static final String RABBIT_HOST = System.getProperty("rabbit.host", "localhost");
    public static final int RABBIT_PORT = Integer.parseInt(System.getProperty("rabbit.port", "5672"));
    public static final String RABBIT_USER = System.getProperty("rabbit.user", "guest");
    public static final String RABBIT_PASS = System.getProperty("rabbit.pass", "guest");

    public static Connection newConnection() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);
        factory.setPort(RABBIT_PORT);
        factory.setUsername(RABBIT_USER);
        factory.setPassword(RABBIT_PASS);
        factory.setAutomaticRecoveryEnabled(true);
        return factory.newConnection();
    }
}
