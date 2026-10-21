package com.ecommerce.promocoes;

import com.ecommerce.common.Catalogo;
import com.ecommerce.common.Produto;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class EmailTemplates {

    private EmailTemplates() {
    }

    static String promocao(Produto produto, int desconto) {
        double precoPromocional = produto.preco * (100 - desconto) / 100.0;
        return layout(
                "<p style=\"margin:0 0 4px;color:#6b6785;font-size:13px;text-transform:uppercase;letter-spacing:.06em\">"
                        + "Categoria " + produto.categoria + " · " + Catalogo.nomeCategoria(produto.categoria) + "</p>"
                        + "<h1 style=\"margin:0 0 16px;font-size:24px\">" + escapar(produto.nome) + "</h1>"
                        + "<p style=\"margin:0;font-size:15px;color:#6b6785;text-decoration:line-through\">" + reais(produto.preco) + "</p>"
                        + "<p style=\"margin:0 0 16px;font-size:32px;font-weight:700;color:#138a52\">" + reais(precoPromocional)
                        + " <span style=\"font-size:16px;background:#e5f6ee;border-radius:999px;padding:2px 10px\">-" + desconto + "%</span></p>"
                        + "<p style=\"margin:0;color:#3a3650\">Promoção por tempo limitado. Aproveite na loja!</p>");
    }

    static String confirmacao(Set<String> categorias) {
        String lista = categorias.stream()
                .map(c -> "<li>Categoria " + escapar(c) + " · " + escapar(Catalogo.nomeCategoria(c)) + "</li>")
                .collect(Collectors.joining());
        return layout(
                "<h1 style=\"margin:0 0 12px;font-size:22px\">Inscrição confirmada</h1>"
                        + "<p style=\"margin:0 0 8px;color:#3a3650\">Você vai receber por e-mail as promoções destas categorias:</p>"
                        + "<ul style=\"margin:0 0 8px;color:#3a3650\">" + lista + "</ul>"
                        + "<p style=\"margin:0;color:#6b6785;font-size:13px\">Para parar de receber, cancele a inscrição na página de promoções da loja.</p>");
    }

    private static String layout(String conteudo) {
        return "<div style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f3f1fb;padding:24px\">"
                + "<div style=\"max-width:480px;margin:0 auto;background:#ffffff;border-radius:14px;padding:28px;color:#1d1a2e\">"
                + "<p style=\"margin:0 0 20px;font-weight:700;color:#5b3df5\">Loja SD · E-commerce distribuído</p>"
                + conteudo
                + "</div></div>";
    }

    private static String reais(double valor) {
        return String.format(Locale.forLanguageTag("pt-BR"), "R$ %,.2f", valor);
    }

    private static String escapar(String texto) {
        return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
