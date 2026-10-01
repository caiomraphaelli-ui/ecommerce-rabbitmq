package com.ecommerce.principal;

public enum PedidoStatus {
    CRIADO("Pedido criado, verificando estoque", false),
    ESTOQUE_CONFIRMADO("Estoque confirmado, gerando cobrança", false),
    AGUARDANDO_PAGAMENTO("Aguardando pagamento", false),
    PAGAMENTO_APROVADO("Pagamento aprovado, preparando envio", false),
    ENVIADO("Pedido enviado", true),
    CANCELADO_ESTOQUE("Cancelado: produto indisponível em estoque", true),
    PAGAMENTO_RECUSADO("Cancelado: pagamento recusado", true),
    CANCELADO_USUARIO("Cancelado pelo cliente", true);

    public final String descricao;
    /** true quando o pedido não muda mais de status. */
    public final boolean finalizado;

    PedidoStatus(String descricao, boolean finalizado) {
        this.descricao = descricao;
        this.finalizado = finalizado;
    }

    /** O cliente só pode cancelar enquanto o pagamento não foi aprovado. */
    public boolean cancelavel() {
        return this == CRIADO || this == ESTOQUE_CONFIRMADO || this == AGUARDANDO_PAGAMENTO;
    }
}
