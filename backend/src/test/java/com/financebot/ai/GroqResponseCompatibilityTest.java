package com.financebot.ai;

import com.financebot.dto.ExpenseExtraction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova que a extração funciona contra a <b>resposta real de um provedor
 * compatível com a OpenAI</b> (caso concreto: Groq), que devolve campos que
 * nossos DTOs não mapeiam — {@code usage}, {@code system_fingerprint},
 * {@code x_groq}, {@code index}, {@code logprobs}, {@code type}.
 *
 * <p>Usa o {@code JsonMapper} e o {@code OpenAiClient} reais do contexto do
 * Spring, e não instâncias genéricas: é isso que garante que o Jackson do
 * Boot ignora campos desconhecidos. Se alguém tornar o mapeamento estrito,
 * este teste falha antes de quebrar em produção.
 */
@SpringBootTest
class GroqResponseCompatibilityTest {

    /** Payload no formato do Groq, com todos os campos extras que ele injeta. */
    private static final String RESPOSTA_GROQ = """
            {
              "id": "chatcmpl-abc123",
              "object": "chat.completion",
              "created": 1760620000,
              "model": "openai/gpt-oss-20b",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_9f2",
                        "type": "function",
                        "function": {
                          "name": "registrar_despesa",
                          "arguments": "{\\"amount\\": 42.5, \\"description\\": \\"Mercado\\", \\"merchant\\": \\"Pao de Acucar\\", \\"payment_method\\": \\"debito\\"}"
                        }
                      }
                    ]
                  },
                  "logprobs": null
                }
              ],
              "system_fingerprint": "fp_groq_abc",
              "usage": {
                "queue_time": 0.0431,
                "prompt_tokens": 258,
                "completion_tokens": 41,
                "total_tokens": 299,
                "prompt_tokens_details": null,
                "completion_tokens_details": null
              },
              "x_groq": {
                "id": "req_01JABC",
                "usage": {"queue_time": 0.0431}
              }
            }""";

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private OpenAiClient client;

    @Test
    void deveIgnorarCamposDesconhecidosDoProvedorEDesenharOToolCall() {
        OpenAiClient.ChatCompletionResponse resposta =
                jsonMapper.readValue(RESPOSTA_GROQ, OpenAiClient.ChatCompletionResponse.class);

        String arguments = resposta.firstToolCallArguments();

        assertThat(arguments)
                .contains("\"amount\"")
                .contains("Pao de Acucar");

        ExpenseExtraction extraction = client.parseExtraction(arguments);

        assertThat(extraction.amount()).isEqualByComparingTo("42.5");
        assertThat(extraction.description()).isEqualTo("Mercado");
        assertThat(extraction.merchant()).isEqualTo("Pao de Acucar");
        assertThat(extraction.paymentMethod()).isEqualTo("debito");
        assertThat(extraction.clarificationRequired()).isFalse();
    }
}
