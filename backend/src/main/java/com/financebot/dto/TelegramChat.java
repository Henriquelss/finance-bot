package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Chat do Telegram onde a mensagem foi recebida. */
public record TelegramChat(@JsonProperty("id") Long id) {
}
