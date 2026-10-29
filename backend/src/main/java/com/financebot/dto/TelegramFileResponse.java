package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Resposta envelopada da Bot API para getFile. */
public record TelegramFileResponse(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("result") TelegramFile result,
        @JsonProperty("description") String description) {
}
