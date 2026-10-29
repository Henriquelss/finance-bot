package com.financebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do provedor de IA. O cliente fala o <b>protocolo OpenAI</b>, então
 * funciona tanto com a própria OpenAI quanto com provedores compatíveis — como o
 * Groq (plano gratuito) ou um Whisper auto-hospedado — mudando apenas a base URL,
 * a chave e os nomes dos modelos.
 *
 * <p>Sem chave, as chamadas falham com erro amigável ao usuário (a aplicação
 * sobe normalmente).
 */
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    /** Base URL do provedor compatível com a API da OpenAI (termina em /v1). */
    private String baseUrl = "https://api.openai.com/v1";

    /** Chave da API. Vazio = integração indisponível. */
    private String apiKey = "";

    /** Modelo usado na transcrição de áudio. */
    private String transcriptionModel = "whisper-1";

    /**
     * Modelo usado na extração estruturada da despesa. Precisa de function
     * calling confiável: no Groq (plano gratuito) usa-se
     * {@code openai/gpt-oss-120b}.
     */
    private String extractionModel = "gpt-4o-mini";

    /**
     * Idioma fixo para a transcrição (ex.: {@code pt}). Vazio = detecção automática.
     * Fixar reduz o tempo de chamada e evita que áudios curtos sejam detectados
     * como outro idioma.
     */
    private String transcriptionLanguage = "";

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTranscriptionModel() {
        return transcriptionModel;
    }

    public void setTranscriptionModel(String transcriptionModel) {
        this.transcriptionModel = transcriptionModel;
    }

    public String getExtractionModel() {
        return extractionModel;
    }

    public void setExtractionModel(String extractionModel) {
        this.extractionModel = extractionModel;
    }

    public String getTranscriptionLanguage() {
        return transcriptionLanguage;
    }

    public void setTranscriptionLanguage(String transcriptionLanguage) {
        this.transcriptionLanguage = transcriptionLanguage;
    }
}
