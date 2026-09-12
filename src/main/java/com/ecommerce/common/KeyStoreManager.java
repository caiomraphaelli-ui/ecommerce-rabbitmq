package com.ecommerce.common;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Responsável por carregar, a partir da pasta do próprio processo em keys/:
 *  - sua própria chave privada (usada para assinar os eventos que publica);
 *  - as chaves públicas de todos os demais microsserviços (usadas para
 *    verificar a assinatura dos eventos que consome).
 *
 * Estrutura de pastas (uma pasta por microsserviço, cada uma contendo as
 * chaves públicas de todos os demais):
 *
 *   keys/
 *     principal/
 *       private_key.pem                 (chave privada, só do principal)
 *       public_keys/estoque.pem, pagamento.pem, entrega.pem, promocoes.pem
 *     estoque/
 *       private_key.pem
 *       public_keys/principal.pem, pagamento.pem, entrega.pem, promocoes.pem
 *     pagamento/, entrega/, promocoes/  (idem)
 *     consumidor-c1/, consumidor-c2/
 *       public_keys/  (públicas dos 5 microsserviços; sem chave privada, pois não publicam)
 *
 * Cada processo lê apenas a própria pasta.
 */
public class KeyStoreManager {

    private static final Path KEYS_ROOT = Paths.get("keys");
    public static final String ARQUIVO_CHAVE_PRIVADA = "private_key.pem";
    public static final String PASTA_CHAVES_PUBLICAS = "public_keys";

    private final PrivateKey chavePrivadaPropria;
    private final Map<String, PublicKey> chavesPublicas = new HashMap<>();

    /**
     * @param processo       nome da pasta do processo em keys/ (ex.: "estoque",
     *                       "consumidor-c1").
     * @param publicaEventos true se o processo publica eventos e, portanto,
     *                       precisa carregar sua chave privada.
     */
    public KeyStoreManager(String processo, boolean publicaEventos) {
        Path pasta = pastaDe(processo);
        try {
            if (publicaEventos) {
                this.chavePrivadaPropria = CryptoUtils.carregarChavePrivada(pasta.resolve(ARQUIVO_CHAVE_PRIVADA));
            } else {
                this.chavePrivadaPropria = null;
            }
            for (String ms : RabbitConfig.MICROSSERVICOS) {
                if (ms.equals(processo)) {
                    continue;
                }
                Path pubPath = pasta.resolve(PASTA_CHAVES_PUBLICAS).resolve(ms + ".pem");
                chavesPublicas.put(ms, CryptoUtils.carregarChavePublica(pubPath));
            }
        } catch (Exception e) {
            throw new RuntimeException("Erro ao carregar chaves de " + pasta + ". Execute primeiro o KeyGeneratorTool. " + e.getMessage(), e);
        }
    }

    public static Path pastaDe(String processo) {
        return KEYS_ROOT.resolve(processo);
    }

    public PrivateKey getChavePrivadaPropria() {
        if (chavePrivadaPropria == null) {
            throw new IllegalStateException("Este processo não possui chave privada configurada (não publica eventos).");
        }
        return chavePrivadaPropria;
    }

    public PublicKey getChavePublicaDe(String microsservico) {
        PublicKey pk = chavesPublicas.get(microsservico);
        if (pk == null) {
            throw new IllegalStateException("Chave pública de '" + microsservico + "' não encontrada.");
        }
        return pk;
    }
}
