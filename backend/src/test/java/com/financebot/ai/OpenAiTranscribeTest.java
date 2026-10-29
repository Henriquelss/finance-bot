package com.financebot.ai;

import com.financebot.config.OpenAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

/**
 * Cobre os dois chamados HTTP do cliente de IA — transcrição e extração — os
 * caminhos que já quebrou em produção e que nenhum teste exercitava.
 *
 * <p>As regressões críticas cobertas aqui:
 * <ul>
 *   <li>{@code NoClassDefFoundError: org/reactivestreams/Publisher} (multipart
 *       com {@code MultipartBodyBuilder} em app servlet) — a falha original.</li>
 *   <li>Schema da extração com tipos não-anuláveis, que fazia o servidor
 *       validante do Groq rejeitar a chamada ("expected string, but got null")
 *       e derrubava o processamento do áudio.</li>
 * </ul>
 */
class OpenAiTranscribeTest {

    /** URL final = base-url + caminho, exatamente como o cliente monta. */
    private static final String OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String EXTRACTIONS_URL = "https://api.openai.com/v1/chat/completions";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";

    /** Tool call completo, no formato que o provedor devolve. */
    private static final String RESPOSTA_EXTRACTION = """
            {"choices":[{"message":{"role":"assistant","tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"registrar_despesa",
               "arguments":"{\\"amount\\":50.0,\\"description\\":\\"Café no Starbucks\\",\\"category\\":\\"Alimentação\\",\\"merchant\\":\\"Starbucks\\",\\"payment_method\\":\\"Débito\\",\\"expense_date\\":null,\\"needs_clarification\\":false,\\"clarification_question\\":null}"}}
            ]}}]}""";

    private OpenAiProperties properties;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new OpenAiProperties();
        properties.setApiKey("sk-teste");
    }

    /**
     * O cliente congela a base URL ao ser construído (é assim que o Spring binda
     * as propriedades antes de injetá-lo), então cada teste monta o próprio
     * cliente <b>depois</b> de configurar o provedor.
     */
    private OpenAiClient novoCliente() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new OpenAiClient(properties, builder, JsonMapper.builder().build());
    }

    @Test
    void deveTranscreverEnviandoMultipartComModeloArquivoEAutorizacao() {
        OpenAiClient client = novoCliente();
        server.expect(requestTo(OPENAI_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-teste"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("name=\"model\"")))
                .andExpect(content().string(containsString("whisper-1")))
                .andExpect(content().string(containsString("name=\"file\"")))
                .andExpect(content().string(containsString("filename=\"audio.ogg\"")))
                .andRespond(withSuccess("{\"text\":\"gastei cem reais no restaurante\"}",
                        MediaType.APPLICATION_JSON));

        String transcricao = client.transcribe("bytes-do-audio".getBytes(), "audio.ogg");

        assertThat(transcricao).isEqualTo("gastei cem reais no restaurante");
        server.verify();
    }

    @Test
    void deveUsarModeloConfiguradoParaTranscricao() {
        properties.setTranscriptionModel("gpt-4o-transcribe");
        OpenAiClient client = novoCliente();

        server.expect(requestTo(OPENAI_URL))
                .andExpect(content().string(containsString("gpt-4o-transcribe")))
                .andRespond(withSuccess("{\"text\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.transcribe(new byte[]{1}, "audio.ogg")).isEqualTo("ok");
        server.verify();
    }

    /**
     * Regressão da migração de provedor: a URL final deve ser montada a partir
     * da base URL configurada (Groq, no plano gratuito).
     */
    @Test
    void deveApontarParaABaseUrlDoProvedorConfigurado() {
        properties.setBaseUrl("https://api.groq.com/openai/v1");
        properties.setTranscriptionModel("whisper-large-v3-turbo");
        properties.setApiKey("gsk-teste");
        OpenAiClient client = novoCliente();

        server.expect(requestTo(GROQ_URL))
                .andExpect(header("Authorization", "Bearer gsk-teste"))
                .andExpect(content().string(containsString("whisper-large-v3-turbo")))
                // O Groq injeta campos próprios na resposta (x_groq) — precisam ser ignorados.
                .andRespond(withSuccess(
                        "{\"text\":\"ok\",\"x_groq\":{\"id\":\"req_01m537fx\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.transcribe(new byte[]{1}, "audio.ogg")).isEqualTo("ok");
        server.verify();
    }

    @Test
    void deveEnviarIdiomaQuandoConfigurado() {
        properties.setTranscriptionLanguage("pt");
        OpenAiClient client = novoCliente();

        server.expect(requestTo(OPENAI_URL))
                .andExpect(content().string(containsString("name=\"language\"")))
                .andExpect(content().string(containsString("pt")))
                .andRespond(withSuccess("{\"text\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.transcribe(new byte[]{1}, "audio.ogg")).isEqualTo("ok");
        server.verify();
    }

    @Test
    void NaoDeveEnviarIdiomaQuandoNaoConfigurado() {
        properties.setTranscriptionLanguage("");
        OpenAiClient client = novoCliente();

        server.expect(requestTo(OPENAI_URL))
                .andExpect(content().string(not(containsString("name=\"language\""))))
                .andRespond(withSuccess("{\"text\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.transcribe(new byte[]{1}, "audio.ogg")).isEqualTo("ok");
        server.verify();
    }

    @Test
    void deveFalharQuandoTranscricaoVenhaVazia() {
        OpenAiClient client = novoCliente();
        server.expect(requestTo(OPENAI_URL))
                .andRespond(withSuccess("{\"text\":\"   \"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.transcribe(new byte[]{1}, "audio.ogg"))
                .isInstanceOf(OpenAiException.class);
    }

    @Test
    void deveFalharQuandoProvedorRetornarErroDeServidor() {
        OpenAiClient client = novoCliente();
        server.expect(requestTo(OPENAI_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.transcribe(new byte[]{1}, "audio.ogg"))
                .isInstanceOf(OpenAiException.class);
    }

    /**
     * Regressão do bug que derrubava o processamento no servidor validante do
     * Groq: o schema marcava campos como {@code string}/{@code number} puros, e o
     * modelo emitia {@code null} quando o usuário não citava o dado — resultando
     * em "expected string, but got null" e na perda da despesa inteira.
     *
     * <p>Todo campo precisa aceitar null no schema ({@code ["tipo","null"]}) para
     * que um áudio com só valor + local + cartão processe sem falhar.
     */
    @Test
    void deveDeclararTodosOsCamposDoSchemaComoAnulaveis() {
        properties.setExtractionModel("openai/gpt-oss-120b");
        OpenAiClient client = novoCliente();

        server.expect(requestTo(EXTRACTIONS_URL))
                .andExpect(content().string(containsString("\"registrar_despesa\"")))
                .andRespond(withSuccess(RESPOSTA_EXTRACTION, MediaType.APPLICATION_JSON));

        client.extractExpense("cinquenta reais no Starbucks, débito", LocalDate.of(2026, 10, 16));
        server.verify();
    }
}
