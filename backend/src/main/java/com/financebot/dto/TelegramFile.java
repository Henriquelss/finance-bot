package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Arquivo do Telegram, com o caminho para download. */
public record TelegramFile(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("file_path") String filePath,
        @JsonProperty("file_size") Integer fileSize) {
}
