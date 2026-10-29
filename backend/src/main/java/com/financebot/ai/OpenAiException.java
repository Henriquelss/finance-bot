package com.financebot.ai;

/** Falha na comunicação ou na resposta da OpenAI. Mensagem segura para log. */
public class OpenAiException extends RuntimeException {

    public OpenAiException(String message) {
        super(message);
    }

    public OpenAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
