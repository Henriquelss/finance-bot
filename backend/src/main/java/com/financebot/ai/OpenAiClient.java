package com.financebot.ai;

import com.financebot.config.OpenAiProperties;
import com.financebot.dto.ExpenseExtraction;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integração com um provedor de IA compatível com a API da OpenAI: transcrição
 * de áudio (Whisper) e extração estruturada da despesa via function calling.
 *
 * <p>A base URL, a chave e os modelos vêm de {@link OpenAiProperties}, então o
 * mesmo cliente serve a OpenAI, o Groq (plano gratuito) ou um Whisper
 * auto-hospedado — o protocolo é o mesmo.
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    static final String EXTRACTION_FUNCTION = "registrar_despesa";

    private static final List<String> CATEGORIES = List.of(
            "Alimentação", "Mercado", "Transporte", "Moradia", "Saúde",
            "Lazer", "Compras", "Educação", "Serviços", "Outros");

    /**
     * Guia de inferência de categoria. O usuário não fala a categoria — ele fala
     * o local — então a IA deve derivá-la do estabelecimento.
     */
    private static final String CATEGORY_GUIDANCE =
            "Categoria inferida do local/descrição. Use sempre a mais específica possível: "
            + "Alimentação (restaurante, café, delivery, lanchonete, padaria, bar), "
            + "Mercado (supermercado, açougue, hortifruti), "
            + "Transporte (uber, 99, posto, estacionamento, pedágio, metrô), "
            + "Moradia (aluguel, condomínio, energia, água, internet), "
            + "Saúde (farmácia, médico, exame, academia), "
            + "Lazer (cinema, bar, show, jogo, streaming), "
            + "Compras (roupa, eletrônico, presente, loja), "
            + "Educação (curso, faculdade, livro), "
            + "Serviços (salão, barbeiro, lavagem, mecânica), "
            + "Outros apenas se não houver qualquer pista.";

    private final OpenAiProperties properties;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public OpenAiClient(OpenAiProperties properties, RestClient.Builder restClientBuilder, JsonMapper jsonMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
        this.jsonMapper = jsonMapper;
    }

    /**
     * Transcreve o áudio enviado (OGG/MP3 do Telegram) em texto.
     *
     * <p>O multipart é montado com {@link MultiValueMap} e não com
     * {@code MultipartBodyBuilder}: no Spring Framework 7 essa classe carrega
     * {@code org.reactivestreams.Publisher} em tempo de execução, e esse artefato
     * <b>não existe</b> em aplicações servlet ({@code spring-boot-starter-web}).
     * Usá-la derrubava o polling com {@code NoClassDefFoundError} no primeiro áudio.
     *
     * @throws OpenAiException se o provedor estiver indisponível ou retornar erro
     */
    public String transcribe(byte[] audio, String filename) {
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("model", properties.getTranscriptionModel());
        if (StringUtils.hasText(properties.getTranscriptionLanguage())) {
            multipart.add("language", properties.getTranscriptionLanguage());
        }
        multipart.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        TranscriptionResponse response;
        try {
            response = restClient.post()
                    .uri("/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(multipart)
                    .retrieve()
                    .body(TranscriptionResponse.class);
        } catch (RestClientException e) {
            log.warn("Falha na transcrição em {}: {}", properties.getBaseUrl(), e.getMessage());
            throw new OpenAiException("Falha ao transcrever o áudio", e);
        }

        if (response == null || !StringUtils.hasText(response.text())) {
            throw new OpenAiException("A transcrição retornou vazia");
        }
        return response.text();
    }

    /**
     * Interpreta a transcrição como dados estruturados de despesa usando
     * function calling. A IA nunca acessa o banco; o back-end valida tudo.
     *
     * @param today data local de recebimento, usada para resolver "ontem"
     * @throws OpenAiException se o provedor falhar ou responder em formato inesperado
     */
    public ExpenseExtraction extractExpense(String transcript, LocalDate today) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getExtractionModel());
        body.put("temperature", 0);
        body.put("tools", List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", EXTRACTION_FUNCTION,
                        "description", "Registra uma despesa extraída do áudio",
                        "parameters", extractionSchema()))));
        body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", EXTRACTION_FUNCTION)));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt(today)),
                Map.of("role", "user", "content", transcript)));

        ChatCompletionResponse response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        } catch (RestClientException e) {
            log.warn("Falha na interpretação em {}: {}", properties.getBaseUrl(), e.getMessage());
            throw new OpenAiException("Falha ao interpretar a despesa", e);
        }

        String arguments = response == null
                ? null
                : response.firstToolCallArguments();
        if (!StringUtils.hasText(arguments)) {
            throw new OpenAiException("A IA não retornou estrutura de despesa");
        }
        return parseExtraction(arguments);
    }

    /**
     * Converte o JSON devolvido pela função da IA em {@link ExpenseExtraction}.
     *
     * @throws OpenAiException se o JSON for inválido
     */
    public ExpenseExtraction parseExtraction(String json) {
        try {
            return jsonMapper.readValue(json, ExpenseExtraction.class);
        } catch (JacksonException e) {
            log.warn("JSON de despesa inválido do provedor de IA: {}", e.getMessage());
            throw new OpenAiException("A IA retornou dados de despesa em formato inválido", e);
        }
    }

    /**
     * Schema da função de extração.
     *
     * <p><b>Por que todos os campos são {@code ["tipo","null"]} e todos constam em
     * {@code required}:</b> provedores com servidor validante (Groq) rejeitam a
     * chamada inteira se o modelo emitir {@code null} para um campo cujo tipo não
     * permite nulo. Como o usuário típico cita apenas <b>valor + local + cartão</b>,
     * campos como {@code expense_date} frequentemente não têm valor — e a resposta
     * era descartada com {@code expected string, but got null}, derrubando o
     * processamento.
     *
     * <p>Declarar o tipo como anulável <b>e</b> exigir o campo resolve os dois lados:
     * o validador aceita nulos, e o modelo é obrigado a preencher tudo (usando
     * {@code null} quando a informação não existe no áudio). Sempre que o modelo
     * podia omitir um campo opcional, ele simplesmente não o emitia — daí o
     * {@code category} nulo em 100% das extrações e a categoria "Outros" no banco.
     */
    private Map<String, Object> extractionSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(Map.of(
                "amount", Map.of("type", List.of("number", "null"),
                        "description", "Valor positivo em reais, ex.: 100.0. null se não citado."),
                "description", Map.of("type", List.of("string", "null"),
                        "description", "Frase curta descrevendo a compra, ex.: Café no Starbucks. null se não der para descrever."),
                "category", Map.of("type", List.of("string", "null"), "enum", categoryEnum(),
                        "description", CATEGORY_GUIDANCE),
                "payment_method", Map.of("type", List.of("string", "null"),
                        "description", "Cartão ou forma de pagamento citada, ex.: BTG, Nubank, débito, crédito, Pix. null se não citado."),
                "merchant", Map.of("type", List.of("string", "null"),
                        "description", "Local ou estabelecimento citado, ex.: Starbucks, Uber. Copie exatamente. null se não citado."),
                "expense_date", Map.of("type", List.of("string", "null"),
                        "description", "Data da despesa no formato YYYY-MM-DD. null se não citada."),
                "needs_clarification", Map.of("type", List.of("boolean", "null"),
                        "description", "true APENAS se o VALOR faltar ou for ambíguo."),
                "clarification_question", Map.of("type", List.of("string", "null"),
                        "description", "Pergunta curta em português, só se needs_clarification for true."))));
        // Todos obrigatórios: força o modelo a emitir cada campo (com null quando ausente).
        schema.put("required", List.of(
                "amount", "description", "category", "payment_method",
                "merchant", "expense_date", "needs_clarification", "clarification_question"));
        return schema;
    }

    /** Enum com o nulo explícito, coerente com o tipo anulável da categoria. */
    private List<Object> categoryEnum() {
        List<Object> values = new ArrayList<>(CATEGORIES);
        values.add(null);
        return values;
    }

    private String systemPrompt(LocalDate today) {
        return """
                Você extrai dados de despesas de transcrições de áudios enviados a um bot de controle financeiro.
                Hoje é %s (fuso America/Sao_Paulo). Se a pessoa disser "ontem" ou "anteontem", calcule a data a partir de hoje.
                O usuário costuma dizer apenas o VALOR, o LOCAL e o CARTÃO usado. Ex.: "cinquenta reais no Starbucks, paguei no débito".
                SEMPRE preencha TODOS os campos da função, usando null quando a informação não existir no áudio. Nunca omita campos.
                INFERIR a categoria a partir do estabelecimento: restaurante/café/lanchonete = Alimentação, supermercado = Mercado,
                app de corrida/posto = Transporte, farmácia = Saúde, cinema/show = Lazer, loja = Compras.
                NUNCA devolva categoria "Outros" se houver qualquer pista do tipo de gasto.
                Se o VALOR faltar ou for ambíguo, defina needs_clarification como true e escreva em clarification_question
                uma pergunta curta, em português. Não pergunte por local nem por cartão: se não forem citados, deixe-os como null.
                """.formatted(today);
    }

    // ------------------------------------------------------------------
    // DTOs internos das respostas da OpenAI
    // ------------------------------------------------------------------

    record TranscriptionResponse(@JsonProperty("text") String text) {
    }

    record ChatCompletionResponse(@JsonProperty("choices") List<Choice> choices) {

        String firstToolCallArguments() {
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            return choices.getFirst().message().firstToolCallArguments();
        }
    }

    record Choice(@JsonProperty("message") ChatChoiceMessage message) {
    }

    record ChatChoiceMessage(@JsonProperty("tool_calls") List<ToolCall> toolCalls) {

        String firstToolCallArguments() {
            if (toolCalls == null || toolCalls.isEmpty()) {
                return null;
            }
            return toolCalls.getFirst().function().arguments();
        }
    }

    record ToolCall(@JsonProperty("function") FunctionCall function) {
    }

    record FunctionCall(@JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {
    }
}
