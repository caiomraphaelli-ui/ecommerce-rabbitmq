package com.ecommerce.keys;

import com.ecommerce.common.CryptoUtils;
import com.ecommerce.common.KeyStoreManager;
import com.ecommerce.common.RabbitConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ferramenta executada UMA VEZ, antes de iniciar os microsserviços.
 * Gera um par de chaves RSA para cada microsserviço e cria uma pasta por
 * processo contendo as chaves públicas de todos os demais microsserviços:
 *
 *   keys/<microsservico>/private_key.pem
 *   keys/<microsservico>/public_keys/<outro microsservico>.pem
 *   keys/consumidor-c1/public_keys/<microsservico>.pem   (idem para consumidor-c2)
 *
 * Cada microsserviço usa sua própria private_key.pem para assinar os eventos
 * que publica, e as chaves de public_keys/ para verificar as assinaturas dos
 * eventos que consome.
 */
public class KeyGeneratorTool {

    private static final String[] CONSUMIDORES = {
            RabbitConfig.CONSUMIDOR_C1,
            RabbitConfig.CONSUMIDOR_C2
    };

    public static void main(String[] args) throws Exception {
        System.out.println("Gerando pares de chaves RSA (2048 bits) em: " + Paths.get("keys").toAbsolutePath());

        Map<String, KeyPair> pares = new LinkedHashMap<>();
        for (String ms : RabbitConfig.MICROSSERVICOS) {
            pares.put(ms, CryptoUtils.gerarParDeChaves());
        }

        for (String ms : RabbitConfig.MICROSSERVICOS) {
            Path pasta = KeyStoreManager.pastaDe(ms);
            CryptoUtils.salvarChavePrivada(pares.get(ms).getPrivate(), pasta.resolve(KeyStoreManager.ARQUIVO_CHAVE_PRIVADA));
            salvarChavesPublicasDosDemais(pares, ms, pasta);
            System.out.println(" -> " + ms + ": chave privada e chaves públicas dos demais em " + pasta);
        }
        for (String consumidor : CONSUMIDORES) {
            Path pasta = KeyStoreManager.pastaDe(consumidor);
            salvarChavesPublicasDosDemais(pares, consumidor, pasta);
            System.out.println(" -> " + consumidor + ": chaves públicas dos microsserviços em " + pasta);
        }

        System.out.println("Concluído.");
    }

    private static void salvarChavesPublicasDosDemais(Map<String, KeyPair> pares, String dono, Path pasta) throws Exception {
        for (Map.Entry<String, KeyPair> par : pares.entrySet()) {
            if (par.getKey().equals(dono)) {
                continue;
            }
            Path destino = pasta.resolve(KeyStoreManager.PASTA_CHAVES_PUBLICAS).resolve(par.getKey() + ".pem");
            CryptoUtils.salvarChavePublica(par.getValue().getPublic(), destino);
        }
    }
}
