package com.ecommerce.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** Configurações de variáveis de ambiente ou do arquivo .env (a variável de ambiente tem prioridade). */
public final class Ambiente {

    private static final Map<String, String> ARQUIVO_ENV = carregarEnv(Paths.get(".env"));

    private Ambiente() {
    }

    public static String get(String chave) {
        String valor = System.getenv(chave);
        if (valor == null || valor.isBlank()) {
            valor = ARQUIVO_ENV.get(chave);
        }
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    public static String get(String chave, String padrao) {
        String valor = get(chave);
        return valor == null ? padrao : valor;
    }

    private static Map<String, String> carregarEnv(Path arquivo) {
        Map<String, String> valores = new HashMap<>();
        if (!Files.isRegularFile(arquivo)) {
            return valores;
        }
        try {
            for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
                linha = linha.trim();
                int igual = linha.indexOf('=');
                if (linha.isEmpty() || linha.startsWith("#") || igual <= 0) {
                    continue;
                }
                String valor = linha.substring(igual + 1).trim();
                if (valor.length() >= 2 && (valor.startsWith("\"") && valor.endsWith("\"")
                        || valor.startsWith("'") && valor.endsWith("'"))) {
                    valor = valor.substring(1, valor.length() - 1);
                }
                valores.put(linha.substring(0, igual).trim(), valor);
            }
        } catch (IOException e) {
            System.err.println("[Ambiente] Não foi possível ler " + arquivo.toAbsolutePath() + ": " + e.getMessage());
        }
        return valores;
    }
}
