package com.financebot.ai;

import com.financebot.config.OpenAiProperties;
import com.financebot.dto.ExpenseExtraction;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiClientTest {

    private final OpenAiClient client = new OpenAiClient(
            new OpenAiProperties(), RestClient.builder(), JsonMapper.builder().build());

    @Test
    void deveInterpretarJsonRetornadoPelaIa() {
        String json = """
                {
                  "amount": 100.0,
                  "description": "Restaurante japonês",
                  "category": "Alimentação",
                  "payment_method": "BTG",
                  "merchant": "Sushi Yama",
                  "expense_date": "2026-10-16",
                  "needs_clarification": false,
                  "clarification_question": null
                }""";

        ExpenseExtraction extraction = client.parseExtraction(json);

        assertThat(extraction.amount()).isEqualByComparingTo("100.0");
        assertThat(extraction.description()).isEqualTo("Restaurante japonês");
        assertThat(extraction.category()).isEqualTo("Alimentação");
        assertThat(extraction.paymentMethod()).isEqualTo("BTG");
        assertThat(extraction.merchant()).isEqualTo("Sushi Yama");
        assertThat(extraction.expenseDate()).isEqualTo("2026-10-16");
        assertThat(extraction.clarificationRequired()).isFalse();
    }

    /**
     * Regressão de um bug real: modelos de IA omitem campos opcionais nas
     * arguments da função. Com {@code boolean} primitivo, a ausência de
     * {@code needs_clarification} abortava a despesa inteira com
     * {@code Cannot map `null` into type `boolean`} — e o usuário perdia o
     * lançamento mesmo com valor e descrição corretos.
     */
    @Test
    void deveTratarCampoDeClarificationAusenteComoSemEsclarecimento() {
        String json = """
                {"amount": 33.5, "description": "Uber", "merchant": "Uber"}""";

        ExpenseExtraction extraction = client.parseExtraction(json);

        assertThat(extraction.amount()).isEqualByComparingTo("33.5");
        assertThat(extraction.clarificationRequired()).isFalse();
        assertThat(extraction.clarificationQuestion()).isNull();
    }

    @Test
    void deveAceitarJsonComCamposOpcionaisAusentes() {
        String json = """
                {"amount": 45.9, "description": "Cinema", "needs_clarification": false}""";

        ExpenseExtraction extraction = client.parseExtraction(json);

        assertThat(extraction.amount()).isEqualByComparingTo("45.9");
        assertThat(extraction.description()).isEqualTo("Cinema");
        assertThat(extraction.category()).isNull();
        assertThat(extraction.merchant()).isNull();
        assertThat(extraction.paymentMethod()).isNull();
        assertThat(extraction.expenseDate()).isNull();
    }

    @Test
    void deveSinalizarClarificationQuandoIaPedeEsclarecimento() {
        String json = """
                {"description": "restaurante", "needs_clarification": true,
                 "clarification_question": "Qual foi o valor da despesa?"}""";

        ExpenseExtraction extraction = client.parseExtraction(json);

        assertThat(extraction.needsClarification()).isTrue();
        assertThat(extraction.clarificationRequired()).isTrue();
        assertThat(extraction.clarificationQuestion()).isEqualTo("Qual foi o valor da despesa?");
        assertThat(extraction.amount()).isNull();
    }

    @Test
    void deveLancarErroEmJsonInvalido() {
        assertThatThrownBy(() -> client.parseExtraction("não é json"))
                .isInstanceOf(OpenAiException.class);
    }

    /**
     * Regressão do schema v2: com o tipo anulável, o modelo agora EMITE os campos
     * (com {@code null} quando ausente) em vez de omiti-los. Precisamos garantir
     * que nulos explícitos mapeiam sem erro — é o caminho que antes derrubava a
     * chamada no servidor validante com "expected string, but got null".
     */
    @Test
    void deveAceitarNulosExplicitosNosCamposOpcionais() {
        String json = """
                {
                  "amount": 50.0,
                  "description": "Café no Starbucks",
                  "category": "Alimentação",
                  "payment_method": null,
                  "merchant": "Starbucks",
                  "expense_date": null,
                  "needs_clarification": false,
                  "clarification_question": null
                }""";

        ExpenseExtraction extraction = client.parseExtraction(json);

        assertThat(extraction.amount()).isEqualByComparingTo("50.0");
        assertThat(extraction.paymentMethod()).isNull();
        assertThat(extraction.expenseDate()).isNull();
        assertThat(extraction.clarificationRequired()).isFalse();
    }

    /**
     * O coração do ajuste: o usuário fala só valor + local + cartão e a IA deve
     * INFERIR a categoria a partir do estabelecimento, sem deixar em "Outros".
     */
    @Test
    void deveManterCategoriaInferidaPelaIa() {
        String json = """
                {"amount": 11.5, "description": "Corrida de Uber", "category": "Transporte",
                 "merchant": "Uber", "payment_method": "Nubank", "needs_clarification": false}""";

        ExpenseExtraction extraction = client.parseExtraction(json);

        assertThat(extraction.category()).isEqualTo("Transporte");
        assertThat(extraction.merchant()).isEqualTo("Uber");
        assertThat(extraction.paymentMethod()).isEqualTo("Nubank");
    }
}
