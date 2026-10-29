package com.financebot.telegram;

import com.financebot.config.TelegramProperties;
import com.financebot.dto.TelegramFile;
import com.financebot.dto.TelegramFileResponse;
import com.financebot.dto.TelegramUpdate;
import com.financebot.dto.TelegramUpdatesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente HTTP da Telegram Bot API (long polling). Não usa lib de terceiros.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final TelegramProperties properties;
    private final RestClient restClient;

    public TelegramClient(TelegramProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl("https://api.telegram.org").build();
    }

    /**
     * Consulta atualizações pendentes (long polling).
     *
     * <p>A resposta traz também se a chamada foi bem-sucedida, porque lista vazia
     * pode significar duas coisas bem diferentes: "sem mensagens novas" ou "a API
     * do Telegram está fora do ar". Sem essa distinção o polling não consegue
     * avisar quando ele mesmo parou de funcionar.
     */
    public PollingResult getUpdates(long offset, int timeoutSeconds) {
        try {
            TelegramUpdatesResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/bot{token}/getUpdates")
                            .queryParam("offset", offset)
                            .queryParam("timeout", timeoutSeconds)
                            .queryParam("allowed_updates", "[\"message\"]")
                            .build(properties.getToken()))
                    .retrieve()
                    .body(TelegramUpdatesResponse.class);
            if (response == null || !response.ok()) {
                log.warn("getUpdates retornou erro: {}", response == null ? "resposta vazia" : response.description());
                return PollingResult.falha();
            }
            return new PollingResult(response.result() == null ? List.of() : response.result(), true);
        } catch (RestClientException e) {
            log.warn("Falha ao consultar getUpdates: {}", e.getMessage());
            return PollingResult.falha();
        }
    }

    /** Resultado de um getUpdates: as atualizações e se a chamada funcionou. */
    public record PollingResult(List<TelegramUpdate> updates, boolean ok) {

        static PollingResult falha() {
            return new PollingResult(List.of(), false);
        }
    }

    /** Obtém o caminho do arquivo no servidor de arquivos do Telegram. */
    public Optional<TelegramFile> getFile(String fileId) {
        try {
            TelegramFileResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/bot{token}/getFile")
                            .queryParam("file_id", fileId)
                            .build(properties.getToken()))
                    .retrieve()
                    .body(TelegramFileResponse.class);
            if (response == null || !response.ok() || response.result() == null) {
                log.warn("getFile retornou erro: {}", response == null ? "resposta vazia" : response.description());
                return Optional.empty();
            }
            return Optional.of(response.result());
        } catch (RestClientException e) {
            log.warn("Falha ao obter arquivo do Telegram: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Baixa os bytes do arquivo a partir do caminho retornado por getFile. */
    public Optional<byte[]> downloadFile(String filePath) {
        try {
            byte[] bytes = restClient.get()
                    .uri(URI.create("https://api.telegram.org/file/bot" + properties.getToken() + "/" + filePath))
                    .retrieve()
                    .body(byte[].class);
            return Optional.ofNullable(bytes);
        } catch (RestClientException e) {
            log.warn("Falha ao baixar arquivo do Telegram: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Envia mensagem de texto ao chat. Falhas são logadas sem derrubar o polling. */
    public void sendMessage(long chatId, String text) {
        try {
            restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/bot{token}/sendMessage")
                            .build(properties.getToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Falha ao enviar mensagem ao chat {}: {}", chatId, e.getMessage());
        }
    }
}
