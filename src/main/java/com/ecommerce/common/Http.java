package com.ecommerce.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Http {

    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static final HttpClient CLIENTE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private Http() {
    }

    @FunctionalInterface
    public interface Rota {
        void tratar(HttpExchange ex) throws Exception;
    }

    public static class ErroHttp extends RuntimeException {
        public final int status;

        public ErroHttp(int status, String mensagem) {
            super(mensagem);
            this.status = status;
        }
    }

    // pool de threads: cada conexão SSE ocupa uma thread
    public static HttpServer criarServidor(int porta) throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(porta), 0);
        servidor.setExecutor(Executors.newCachedThreadPool());
        return servidor;
    }

    public static void rota(HttpServer servidor, String caminho, Rota rota) {
        servidor.createContext(caminho, ex -> {
            try {
                cors(ex);
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                rota.tratar(ex);
            } catch (ErroHttp e) {
                responderErro(ex, e.status, e.getMessage());
            } catch (JsonParseException e) {
                responderErro(ex, 400, "JSON inválido");
            } catch (Exception e) {
                System.err.println("[HTTP] Erro em " + ex.getRequestMethod() + " " + ex.getRequestURI() + ": " + e);
                responderErro(ex, 500, "Erro interno: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
    }

    public static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    public static void responderJson(HttpExchange ex, int status, Object corpo) throws IOException {
        responder(ex, status, "application/json; charset=utf-8", GSON.toJson(corpo).getBytes(StandardCharsets.UTF_8));
    }

    public static void responder(HttpExchange ex, int status, String contentType, byte[] corpo) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, corpo.length == 0 ? -1 : corpo.length);
        if (corpo.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(corpo);
            }
        }
    }

    private static void responderErro(HttpExchange ex, int status, String mensagem) {
        try {
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("erro", mensagem);
            responderJson(ex, status, corpo);
        } catch (IOException | IllegalStateException ignorada) {
            // resposta já enviada ou cliente desconectou
        }
    }

    public static <T> T lerJson(HttpExchange ex, Class<T> tipo) throws IOException {
        T valor = GSON.fromJson(lerCorpo(ex), tipo);
        if (valor == null) {
            throw new ErroHttp(400, "Corpo da requisição vazio");
        }
        return valor;
    }

    public static String lerCorpo(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String par : raw.split("&")) {
            int i = par.indexOf('=');
            String chave = i < 0 ? par : par.substring(0, i);
            String valor = i < 0 ? "" : par.substring(i + 1);
            params.put(URLDecoder.decode(chave, StandardCharsets.UTF_8), URLDecoder.decode(valor, StandardCharsets.UTF_8));
        }
        return params;
    }

    public static String subcaminho(HttpExchange ex) {
        String contexto = ex.getHttpContext().getPath();
        String caminho = ex.getRequestURI().getPath();
        String resto = caminho.length() > contexto.length() ? caminho.substring(contexto.length()) : "";
        while (resto.startsWith("/")) {
            resto = resto.substring(1);
        }
        while (resto.endsWith("/")) {
            resto = resto.substring(0, resto.length() - 1);
        }
        return resto;
    }

    public static void exigirMetodo(HttpExchange ex, String... metodos) {
        for (String m : metodos) {
            if (m.equals(ex.getRequestMethod())) {
                return;
            }
        }
        throw new ErroHttp(405, "Método " + ex.getRequestMethod() + " não suportado");
    }

    public static class Resposta {
        public final int status;
        public final String corpo;

        Resposta(int status, String corpo) {
            this.status = status;
            this.corpo = corpo;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public <T> T comoJson(Type tipo) {
            return GSON.fromJson(corpo, tipo);
        }
    }

    public static Resposta get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = CLIENTE.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resposta(resp.statusCode(), resp.body());
    }

    public static Resposta postJson(String url, Object corpo) throws IOException, InterruptedException {
        return postJson(url, corpo, Map.of());
    }

    public static Resposta postJson(String url, Object corpo, Map<String, String> cabecalhos) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(corpo), StandardCharsets.UTF_8));
        cabecalhos.forEach(req::header);
        HttpResponse<String> resp = CLIENTE.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resposta(resp.statusCode(), resp.body());
    }
}
