package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mensagem recebida do Telegram (mínimo necessário). */
public record TelegramMessage(
        @JsonProperty("message_id") long messageId,
        @JsonProperty("from") TelegramUser from,
        @JsonProperty("chat") TelegramChat chat,
        @JsonProperty("date") long date,
        @JsonProperty("voice") TelegramAudio voice,
        @JsonProperty("audio") TelegramAudio audio) {

    /** Retorna o áudio da mensagem, seja voz gravada ou arquivo de áudio. */
    public TelegramAudio audioOrNull() {
        return voice != null ? voice : audio;
    }
}
