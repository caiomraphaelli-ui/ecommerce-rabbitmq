package com.ecommerce.common;

/** Portas e URLs HTTP; podem ser trocadas com -D (ex.: -Dgateway.porta=9090). */
public final class HttpConfig {

    private HttpConfig() {
    }

    public static final int PORTA_GATEWAY = Integer.parseInt(System.getProperty("gateway.porta", "8080"));
    public static final int PORTA_ESTOQUE = Integer.parseInt(System.getProperty("estoque.porta", "8081"));
    public static final int PORTA_PAGAMENTO = Integer.parseInt(System.getProperty("pagamento.porta", "8082"));
    public static final int PORTA_MOCK_PAGAMENTO = Integer.parseInt(System.getProperty("mock.porta", "8090"));

    public static final String URL_ESTOQUE = System.getProperty("estoque.url", "http://localhost:" + PORTA_ESTOQUE);

    public static final String URL_MOCK_PAGAMENTO = System.getProperty("mock.url", "http://localhost:" + PORTA_MOCK_PAGAMENTO);

    public static final String URL_WEBHOOK_PAGAMENTO = System.getProperty("pagamento.webhook.url",
            "http://localhost:" + PORTA_PAGAMENTO + "/webhook/pagamento");

    public static final String PASTA_FRONTEND = System.getProperty("frontend.dir", "frontend");

    public static final String PASTA_DADOS = System.getProperty("dados.dir", "data");
}
