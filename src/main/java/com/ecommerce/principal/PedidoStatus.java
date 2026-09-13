package com.ecommerce.principal;

public enum PedidoStatus {
    CRIADO("Criado, aguardando verificação de estoque"),
    AGUARDANDO_PAGAMENTO("Estoque OK, aguardando pagamento"),
    PAGAMENTO_APROVADO("Pagamento aprovado, aguardando envio"),
    ENVIADO("Enviado"),
    CANCELADO_ESTOQUE("Cancelado: produto indisponível em estoque"),
    CANCELADO_PAGAMENTO("Cancelado: pagamento recusado"),
    EXCLUIDO_PELO_USUARIO("Excluído pelo usuário");

    public final String descricao;

    PedidoStatus(String descricao) {
        this.descricao = descricao;
    }
}
