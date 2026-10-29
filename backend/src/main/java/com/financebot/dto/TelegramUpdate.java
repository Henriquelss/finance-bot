package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Atualização do Telegram contendo uma mensagem. */
public record TelegramUpdate(
        @JsonProperty("update_id") long updateId,
        @JsonProperty("message") TelegramMessage message) {
}
