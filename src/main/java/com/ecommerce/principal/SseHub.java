package com.ecommerce.principal;

import com.ecommerce.common.Http;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Conexões SSE abertas pelo frontend, agrupadas por clienteId (cada aba é uma conexão). */
public class SseHub {

    private static final long INTERVALO_HEARTBEAT_S = 15;

    private final Map<String, Set<Conexao>> conexoesPorCliente = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SseHub() {
        // ": ping" periódico mantém a conexão viva e detecta abas fechadas
        heartbeat.scheduleAtFixedRate(() -> conexoesPorCliente.values()
                        .forEach(conjunto -> conjunto.forEach(c -> c.escrever(": ping\n\n"))),
                INTERVALO_HEARTBEAT_S, INTERVALO_HEARTBEAT_S, TimeUnit.SECONDS);
    }

    private static class Conexao {
        private final OutputStream saida;
        private final CountDownLatch fechada = new CountDownLatch(1);

        Conexao(OutputStream saida) {
            this.saida = saida;
        }

        synchronized boolean escrever(String texto) {
            if (fechada.getCount() == 0) {
                return false;
            }
            try {
                saida.write(texto.getBytes(StandardCharsets.UTF_8));
                saida.flush();
                return true;
            } catch (IOException e) {
                fechada.countDown(); // cliente desconectou
                return false;
            }
        }
    }

    /** Segura a thread até o cliente desconectar (o servidor HTTP usa pool de threads). */
    public void abrir(HttpExchange ex, String clienteId) throws IOException, InterruptedException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0); // tamanho 0 = resposta em streaming (chunked)

        Conexao conexao = new Conexao(ex.getResponseBody());
        conexoesPorCliente.computeIfAbsent(clienteId, k -> ConcurrentHashMap.newKeySet()).add(conexao);
        System.out.println("[SSE] Cliente " + clienteId + " conectado (" + total() + " conexão(ões) abertas)");

        // o navegador reconecta sozinho em 3 s se a conexão cair
        conexao.escrever("retry: 3000\n" + formatar("conectado", Map.of("clienteId", clienteId)));
        try {
            conexao.fechada.await();
        } finally {
            Set<Conexao> conjunto = conexoesPorCliente.get(clienteId);
            if (conjunto != null) {
                conjunto.remove(conexao);
            }
            System.out.println("[SSE] Cliente " + clienteId + " desconectado (" + total() + " conexão(ões) abertas)");
        }
    }

    public int enviar(String clienteId, String evento, Object dados) {
        Set<Conexao> conjunto = conexoesPorCliente.get(clienteId);
        if (conjunto == null) {
            return 0;
        }
        String mensagem = formatar(evento, dados);
        int entregues = 0;
        for (Conexao c : conjunto) {
            if (c.escrever(mensagem)) {
                entregues++;
            }
        }
        return entregues;
    }

    private static String formatar(String evento, Object dados) {
        return "event: " + evento + "\ndata: " + Http.GSON.toJson(dados) + "\n\n";
    }

    private int total() {
        return conexoesPorCliente.values().stream().mapToInt(Set::size).sum();
    }
}
