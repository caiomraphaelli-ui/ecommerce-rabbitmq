package com.ecommerce.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Persistência simples em arquivo JSON dentro da pasta de dados (padrão: data/). */
public class ArquivoJson {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path caminho;

    public ArquivoJson(String nomeArquivo) {
        this.caminho = Paths.get(HttpConfig.PASTA_DADOS, nomeArquivo);
    }

    public Path caminho() {
        return caminho;
    }

    public boolean existe() {
        return Files.exists(caminho);
    }

    /** Lê o arquivo; devolve null se ele não existir. */
    public <T> T ler(Type tipo) throws IOException {
        if (!existe()) {
            return null;
        }
        return GSON.fromJson(new String(Files.readAllBytes(caminho), StandardCharsets.UTF_8), tipo);
    }

    /** Grava em um arquivo temporário e depois troca, para nunca deixar o arquivo pela metade. */
    public synchronized void salvar(Object dados) throws IOException {
        Files.createDirectories(caminho.getParent());
        Path temporario = caminho.resolveSibling(caminho.getFileName() + ".tmp");
        Files.write(temporario, GSON.toJson(dados).getBytes(StandardCharsets.UTF_8));
        Files.move(temporario, caminho, StandardCopyOption.REPLACE_EXISTING);
    }
}
