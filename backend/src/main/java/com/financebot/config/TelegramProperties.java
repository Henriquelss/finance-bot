package com.financebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do bot de Telegram. Se o token estiver vazio, o polling fica
 * desativado e a aplicação sobe normalmente (útil em ambientes de teste).
 */
@ConfigurationProperties(prefix = "telegram")
public class TelegramProperties {

    /** Token do BotFather. Vazio = bot desativado. */
    private String token = "";

    /** Intervalo entre chamadas ao getUpdates, em milissegundos. */
    private long pollingIntervalMs = 2000;

    /** Timeout do long polling, em segundos (máximo da API: 50). */
    private int pollingTimeoutSeconds = 10;

    /** Limite de tamanho de áudio aceito (a API do Telegram limita a 20 MB). */
    private int maxAudioBytes = 20 * 1024 * 1024;

    /**
     * Intervalo mínimo entre logs de atividade do polling, em segundos.
     * Valores menores que 1 desativam o heartbeat.
     */
    private long heartbeatLogSeconds = 900;

    /** Quantas falhas consecutivas no getUpdates antes de avisar no log. */
    private int failedPollsWarnThreshold = 5;

    public boolean isEnabled() {
        return token != null && !token.isBlank();
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public long getPollingIntervalMs() {
        return pollingIntervalMs;
    }

    public void setPollingIntervalMs(long pollingIntervalMs) {
        this.pollingIntervalMs = pollingIntervalMs;
    }

    public int getPollingTimeoutSeconds() {
        return pollingTimeoutSeconds;
    }

    public void setPollingTimeoutSeconds(int pollingTimeoutSeconds) {
        this.pollingTimeoutSeconds = pollingTimeoutSeconds;
    }

    public int getMaxAudioBytes() {
        return maxAudioBytes;
    }

    public void setMaxAudioBytes(int maxAudioBytes) {
        this.maxAudioBytes = maxAudioBytes;
    }

    public long getHeartbeatLogSeconds() {
        return heartbeatLogSeconds;
    }

    public void setHeartbeatLogSeconds(long heartbeatLogSeconds) {
        this.heartbeatLogSeconds = heartbeatLogSeconds;
    }

    public int getFailedPollsWarnThreshold() {
        return failedPollsWarnThreshold;
    }

    public void setFailedPollsWarnThreshold(int failedPollsWarnThreshold) {
        this.failedPollsWarnThreshold = failedPollsWarnThreshold;
    }
}
