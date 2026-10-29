package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Usuário do Telegram que enviou a mensagem. */
public record TelegramUser(
        @JsonProperty("id") Long id,
        @JsonProperty("first_name") String firstName) {
}
