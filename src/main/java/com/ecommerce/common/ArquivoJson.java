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

    public <T> T ler(Type tipo) throws IOException {
        if (!existe()) {
            return null;
        }
        return GSON.fromJson(new String(Files.readAllBytes(caminho), StandardCharsets.UTF_8), tipo);
    }

    // grava num .tmp e troca, para o arquivo nunca ficar pela metade
    public synchronized void salvar(Object dados) throws IOException {
        Files.createDirectories(caminho.getParent());
        Path temporario = caminho.resolveSibling(caminho.getFileName() + ".tmp");
        Files.write(temporario, GSON.toJson(dados).getBytes(StandardCharsets.UTF_8));
        Files.move(temporario, caminho, StandardCopyOption.REPLACE_EXISTING);
    }
}
