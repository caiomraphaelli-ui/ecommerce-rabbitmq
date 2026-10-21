package com.ecommerce.promocoes;

import com.ecommerce.common.Ambiente;
import com.ecommerce.common.Http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cliente da API de e-mail Resend. Sem RESEND_API_KEY, o envio é só simulado no log. */
public class ResendEmail {

    private static final String URL_API = "https://api.resend.com/emails";

    private final String apiKey;
    private final String remetente;

    public ResendEmail() {
        this.apiKey = Ambiente.get("RESEND_API_KEY");
        this.remetente = Ambiente.get("RESEND_FROM", "Ecommerce BC <onboarding@resend.dev>");
    }

    public boolean configurado() {
        return apiKey != null;
    }

    public String remetente() {
        return remetente;
    }

    public String enviar(String destinatario, String assunto, String html) {
        if (!configurado()) {
            return "SIMULADO (defina RESEND_API_KEY para enviar de verdade)";
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("from", remetente);
        corpo.put("to", List.of(destinatario));
        corpo.put("subject", assunto);
        corpo.put("html", html);
        try {
            Http.Resposta resposta = Http.postJson(URL_API, corpo, Map.of("Authorization", "Bearer " + apiKey));
            if (resposta.ok()) {
                return "ENVIADO " + resposta.corpo;
            }
            return "FALHOU (HTTP " + resposta.status + ") " + resposta.corpo;
        } catch (Exception e) {
            return "FALHOU " + e.getMessage();
        }
    }
}
