package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Resposta envelopada da Bot API para getUpdates. */
public record TelegramUpdatesResponse(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("result") List<TelegramUpdate> result,
        @JsonProperty("description") String description) {
}
