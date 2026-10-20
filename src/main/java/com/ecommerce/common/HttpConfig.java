package com.ecommerce.common;

/**
 * Portas e URLs dos componentes que conversam por HTTP. Todas podem ser
 * trocadas com system properties (ex.: -Dgateway.porta=9090).
 */
public final class HttpConfig {

    private HttpConfig() {
    }

    public static final int PORTA_GATEWAY = Integer.parseInt(System.getProperty("gateway.porta", "8080"));
    public static final int PORTA_ESTOQUE = Integer.parseInt(System.getProperty("estoque.porta", "8081"));
    public static final int PORTA_PAGAMENTO = Integer.parseInt(System.getProperty("pagamento.porta", "8082"));
    public static final int PORTA_MOCK_PAGAMENTO = Integer.parseInt(System.getProperty("mock.porta", "8090"));

    /** Usada pelo API Gateway para consultar os produtos (REST). */
    public static final String URL_ESTOQUE = System.getProperty("estoque.url", "http://localhost:" + PORTA_ESTOQUE);

    /** Usada pelo MS Pagamento para criar cobranças no provedor externo (REST). */
    public static final String URL_MOCK_PAGAMENTO = System.getProperty("mock.url", "http://localhost:" + PORTA_MOCK_PAGAMENTO);

    /** URL que o MS Pagamento informa ao provedor para receber o Webhook. */
    public static final String URL_WEBHOOK_PAGAMENTO = System.getProperty("pagamento.webhook.url",
            "http://localhost:" + PORTA_PAGAMENTO + "/webhook/pagamento");

    /** Pasta com os arquivos do frontend (HTML/CSS/JS) servidos pelo Gateway. */
    public static final String PASTA_FRONTEND = System.getProperty("frontend.dir", "frontend");

    /** Pasta onde os microsserviços persistem seus dados. */
    public static final String PASTA_DADOS = System.getProperty("dados.dir", "data");
}
