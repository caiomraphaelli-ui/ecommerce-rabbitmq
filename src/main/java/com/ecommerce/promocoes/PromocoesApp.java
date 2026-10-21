package com.ecommerce.promocoes;

import com.ecommerce.common.*;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.BuiltinExchangeType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

public class PromocoesApp extends ProcessoMensageria {

    private static final int[] DESCONTOS_POSSIVEIS = {10, 15, 20, 25, 30, 40};
    private static final long INTERVALO_MS = Long.parseLong(System.getProperty("promocoes.intervalo.ms", "60000"));
    private static final long PAUSA_ENTRE_EMAILS_MS = 600; // o plano grátis do Resend aceita ~2 requisições/s

    private final ArquivoJson arquivo = new ArquivoJson("interessados.json");
    private final ResendEmail email = new ResendEmail();

    // e-mail -> categorias de interesse
    private Map<String, Set<String>> interessados;

    public PromocoesApp() throws Exception {
        super("Promoções", RabbitConfig.MS_PROMOCOES, true);
        Map<String, Set<String>> salvos = arquivo.ler(new TypeToken<TreeMap<String, LinkedHashSet<String>>>() {}.getType());
        interessados = salvos == null ? new TreeMap<>() : salvos;
    }

    @Override
    public void iniciar() throws Exception {
        declararExchange(RabbitConfig.EXCHANGE_PROMOCOES, BuiltinExchangeType.TOPIC);
        declararExchange(RabbitConfig.EXCHANGE_ECOMMERCE, BuiltinExchangeType.DIRECT);
        declararFila(RabbitConfig.EXCHANGE_ECOMMERCE, RabbitConfig.FILA_PROMOCOES, RabbitConfig.RK_INTERESSE_PROMOCAO);

        registrarTratador(RabbitConfig.RK_INTERESSE_PROMOCAO, Payloads.InteressePromocao.class, this::registrarInteresse);
        consumir(RabbitConfig.FILA_PROMOCOES);

        System.out.println("Microsserviço Promoções iniciado. " + interessados.size() + " interessado(s) cadastrado(s): " + interessados);
        System.out.println("API de e-mail (Resend): " + (email.configurado()
                ? "configurada, remetente " + email.remetente()
                : "SEM RESEND_API_KEY -> envios apenas simulados no log"));
        System.out.println("Gerando uma promoção a cada " + (INTERVALO_MS / 1000) + "s...");
        while (true) {
            Thread.sleep(INTERVALO_MS);
            gerarEPublicarPromocao();
        }
    }

    private void registrarInteresse(Payloads.InteressePromocao interesse) throws IOException {
        String emailNormalizado = interesse.email == null ? "" : interesse.email.trim().toLowerCase();
        if (emailNormalizado.isEmpty()) {
            log("interesse.promocao sem e-mail. Ignorado.");
            return;
        }

        if (Payloads.InteressePromocao.CANCELAR.equals(interesse.acao)) {
            boolean existia;
            synchronized (this) {
                existia = interessados.remove(emailNormalizado) != null;
                arquivo.salvar(interessados);
            }
            log("Interesse CANCELADO para " + emailNormalizado + (existia ? "" : " (não estava inscrito)"));
            return;
        }

        Set<String> categorias = new LinkedHashSet<>(interesse.categorias == null ? List.of() : interesse.categorias);
        synchronized (this) {
            if (categorias.isEmpty()) {
                interessados.remove(emailNormalizado);
            } else {
                interessados.put(emailNormalizado, categorias);
            }
            arquivo.salvar(interessados);
        }
        log("Interesse registrado: " + emailNormalizado + " -> categorias " + categorias);

        String resultado = email.enviar(emailNormalizado, "Inscrição em promoções confirmada",
                EmailTemplates.confirmacao(categorias));
        log("E-mail de confirmação para " + emailNormalizado + ": " + resultado);
    }

    private void gerarEPublicarPromocao() throws IOException, InterruptedException {
        List<Produto> produtos = Catalogo.listar();
        Produto produto = produtos.get(ThreadLocalRandom.current().nextInt(produtos.size()));
        int desconto = DESCONTOS_POSSIVEIS[ThreadLocalRandom.current().nextInt(DESCONTOS_POSSIVEIS.length)];

        Payloads.Promocao payload = new Payloads.Promocao(produto.id, produto.nome, produto.categoria, desconto);
        String routingKey = RabbitConfig.rkPromocaoCategoria(produto.categoria);

        publicar(RabbitConfig.EXCHANGE_PROMOCOES, routingKey, payload);
        log("Publicada promoção: " + produto.nome + " (categoria " + produto.categoria
                + ") com " + desconto + "% de desconto -> routing key: " + routingKey);

        notificarInteressados(produto, desconto);
    }

    private void notificarInteressados(Produto produto, int desconto) throws InterruptedException {
        List<String> destinatarios = new ArrayList<>();
        synchronized (this) {
            interessados.forEach((emailCliente, categorias) -> {
                if (categorias.contains(produto.categoria)) {
                    destinatarios.add(emailCliente);
                }
            });
        }
        if (destinatarios.isEmpty()) {
            log("Nenhum interessado na categoria " + produto.categoria + ". Nenhum e-mail enviado.");
            return;
        }

        String assunto = desconto + "% OFF em " + produto.nome + "!";
        String html = EmailTemplates.promocao(produto, desconto);
        for (String destinatario : destinatarios) {
            String resultado = email.enviar(destinatario, assunto, html);
            log("E-mail de promoção para " + destinatario + ": " + resultado);
            Thread.sleep(PAUSA_ENTRE_EMAILS_MS);
        }
    }

    public static void main(String[] args) throws Exception {
        new PromocoesApp().iniciar();
    }
}
