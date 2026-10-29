package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Áudio anexado à mensagem (voz ou arquivo de áudio). */
public record TelegramAudio(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("file_unique_id") String fileUniqueId,
        @JsonProperty("file_size") Integer fileSize,
        @JsonProperty("duration") Integer duration) {
}
