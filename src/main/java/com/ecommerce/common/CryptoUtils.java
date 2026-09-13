package com.ecommerce.common;

import javax.crypto.Cipher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class CryptoUtils {

    private static final String ALGORITMO_CHAVE = "RSA";
    private static final String ALGORITMO_HASH = "SHA-256";
    private static final String TRANSFORMACAO_RSA = "RSA/ECB/PKCS1Padding";
    private static final int TAMANHO_CHAVE = 2048;

    // Cabeçalho DER do DigestInfo para SHA-256 (RFC 8017, seção 9.2)
    private static final byte[] DIGEST_INFO_SHA256 = {
            0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01,
            0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20
    };

    private CryptoUtils() {
    }

    public static KeyPair gerarParDeChaves() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITMO_CHAVE);
        generator.initialize(TAMANHO_CHAVE);
        return generator.generateKeyPair();
    }

    public static byte[] gerarHash(String conteudo) {
        try {
            return MessageDigest.getInstance(ALGORITMO_HASH).digest(conteudo.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 indisponível", e);
        }
    }

    public static String assinar(String conteudo, PrivateKey chavePrivada) {
        try {
            byte[] hash = gerarHash(conteudo);
            Cipher rsa = Cipher.getInstance(TRANSFORMACAO_RSA);
            rsa.init(Cipher.ENCRYPT_MODE, chavePrivada);
            byte[] assinaturaBytes = rsa.doFinal(digestInfo(hash));
            return Base64.getEncoder().encodeToString(assinaturaBytes);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Erro ao assinar evento", e);
        }
    }

    public static boolean verificar(String conteudo, String assinaturaBase64, PublicKey chavePublica) {
        if (conteudo == null || assinaturaBase64 == null || chavePublica == null) {
            return false;
        }
        try {
            Cipher rsa = Cipher.getInstance(TRANSFORMACAO_RSA);
            rsa.init(Cipher.DECRYPT_MODE, chavePublica);
            byte[] digestInfoAssinado = rsa.doFinal(Base64.getDecoder().decode(assinaturaBase64));
            byte[] digestInfoEsperado = digestInfo(gerarHash(conteudo));
            return MessageDigest.isEqual(digestInfoEsperado, digestInfoAssinado);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] digestInfo(byte[] hash) {
        byte[] resultado = new byte[DIGEST_INFO_SHA256.length + hash.length];
        System.arraycopy(DIGEST_INFO_SHA256, 0, resultado, 0, DIGEST_INFO_SHA256.length);
        System.arraycopy(hash, 0, resultado, DIGEST_INFO_SHA256.length, hash.length);
        return resultado;
    }

    public static void salvarChavePrivada(PrivateKey chave, Path caminho) throws IOException {
        String pem = envolverPem(Base64.getEncoder().encodeToString(chave.getEncoded()), "PRIVATE KEY");
        Files.createDirectories(caminho.getParent());
        Files.write(caminho, pem.getBytes(StandardCharsets.UTF_8));
    }

    public static void salvarChavePublica(PublicKey chave, Path caminho) throws IOException {
        String pem = envolverPem(Base64.getEncoder().encodeToString(chave.getEncoded()), "PUBLIC KEY");
        Files.createDirectories(caminho.getParent());
        Files.write(caminho, pem.getBytes(StandardCharsets.UTF_8));
    }

    public static PrivateKey carregarChavePrivada(Path caminho) throws IOException, GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(desenvolverPem(new String(Files.readAllBytes(caminho), StandardCharsets.UTF_8)));
        KeyFactory kf = KeyFactory.getInstance(ALGORITMO_CHAVE);
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static PublicKey carregarChavePublica(Path caminho) throws IOException, GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(desenvolverPem(new String(Files.readAllBytes(caminho), StandardCharsets.UTF_8)));
        KeyFactory kf = KeyFactory.getInstance(ALGORITMO_CHAVE);
        return kf.generatePublic(new X509EncodedKeySpec(der));
    }

    private static String envolverPem(String base64, String tipo) {
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(tipo).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        sb.append("-----END ").append(tipo).append("-----\n");
        return sb.toString();
    }

    private static String desenvolverPem(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String linha : pem.split("\n")) {
            if (!linha.startsWith("-----")) {
                sb.append(linha.trim());
            }
        }
        return sb.toString();
    }
}
