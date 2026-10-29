package com.financebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Despesa extraída da transcrição pela IA. Campos ausentes podem ser null;
 * a validação e a persistência são sempre feitas pelo back-end, nunca pela IA.
 *
 * <p>Campos booleanos são {@link Boolean} (anuláveis) e não {@code boolean}:
 * modelos de IA frequentemente omitem campos opcionais nas arguments da função,
 * e um primitivo faria o Jackson abortar a despesa inteira com
 * {@code Cannot map `null` into type `boolean`}.
 */
public record ExpenseExtraction(
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("description") String description,
        @JsonProperty("category") String category,
        @JsonProperty("payment_method") String paymentMethod,
        @JsonProperty("merchant") String merchant,
        @JsonProperty("expense_date") String expenseDate,
        @JsonProperty("needs_clarification") Boolean needsClarification,
        @JsonProperty("clarification_question") String clarificationQuestion) {

    /**
     * Apenas {@code true} quando a IA pediu esclarecimento explicitamente.
     * Campo ausente ou nulo = despesa completa, sem esclarecimento.
     */
    public boolean clarificationRequired() {
        return Boolean.TRUE.equals(needsClarification);
    }
}
