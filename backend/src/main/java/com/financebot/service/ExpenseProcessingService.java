package com.financebot.service;

import com.financebot.ai.OpenAiClient;
import com.financebot.ai.OpenAiException;
import com.financebot.config.TelegramProperties;
import com.financebot.dto.ExpenseExtraction;
import com.financebot.dto.TelegramAudio;
import com.financebot.dto.TelegramFile;
import com.financebot.dto.TelegramMessage;
import com.financebot.dto.TelegramUpdate;
import com.financebot.entity.Expense;
import com.financebot.entity.User;
import com.financebot.repository.ExpenseRepository;
import com.financebot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Orquestra o fluxo do PRD: usuário → download do áudio → transcrição →
 * interpretação → validação → persistência → confirmação.
 *
 * A confirmação é enviada somente depois do commit da despesa; falhas externas
 * geram mensagem amigável sem duplicar lançamentos.
 */
@Service
public class ExpenseProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseProcessingService.class);

    static final String PROMPT_AUDIO = """
            Olá! 👋 Me mande um áudio descrevendo uma despesa, por exemplo:
            "Gastei cem reais no restaurante japonês e paguei com o BTG".""";
    static final String ERRO_GENERICO = """
            Desculpe, tive um problema para processar seu áudio. 😕
            Pode tentar mandar novamente?""";

    private final TelegramClient telegramClient;
    private final OpenAiClient openAiClient;
    private final UserService userService;
    private final DateResolutionService dateResolutionService;
    private final ExpenseRepository expenseRepository;
    private final TelegramProperties telegramProperties;

    public ExpenseProcessingService(TelegramClient telegramClient,
                                    OpenAiClient openAiClient,
                                    UserService userService,
                                    DateResolutionService dateResolutionService,
                                    ExpenseRepository expenseRepository,
                                    TelegramProperties telegramProperties) {
        this.telegramClient = telegramClient;
        this.openAiClient = openAiClient;
        this.userService = userService;
        this.dateResolutionService = dateResolutionService;
        this.expenseRepository = expenseRepository;
        this.telegramProperties = telegramProperties;
    }

    /** Processa um update do Telegram. Erros são capturados para não derrubar o polling. */
    public void process(TelegramUpdate update) {
        if (update.message() == null || update.message().chat() == null || update.message().chat().id() == null) {
            return;
        }
        long chatId = update.message().chat().id();

        // Deduplicação: se o update já gerou despesa, não processa de novo
        if (expenseRepository.existsByTelegramUpdateId(update.updateId())) {
            log.debug("Update {} já processado — ignorando", update.updateId());
            return;
        }

        TelegramAudio audio = update.message().audioOrNull();
        if (audio == null) {
            log.info("Update {} sem áudio — enviando instruções ao chat {}",
                    update.updateId(), chatId);
            telegramClient.sendMessage(chatId, PROMPT_AUDIO);
            return;
        }
        if (audio.fileSize() != null && audio.fileSize() > telegramProperties.getMaxAudioBytes()) {
            log.info("Update {}: áudio de {} bytes excede o limite de {} — ignorando",
                    update.updateId(), audio.fileSize(), telegramProperties.getMaxAudioBytes());
            telegramClient.sendMessage(chatId, "Esse áudio é muito grande (máximo: 20 MB). Mande um áudio menor, por favor. 🎤");
            return;
        }

        log.info("Update {}: áudio recebido (chat={}, duração={} s, {} bytes)",
                update.updateId(), chatId, audio.duration(), audio.fileSize());

        try {
            Expense expense = buildExpense(update.message(), audio, update.updateId());
            expenseRepository.saveAndFlush(expense);
            log.info("Update {}: despesa {} registrada (R$ {}, {}, {}, {})",
                    update.updateId(), expense.getId(), formatAmount(expense.getAmount()),
                    expense.getDescription(), expense.getCategory(), expense.getExpenseDate());
            telegramClient.sendMessage(chatId, confirmation(expense));
        } catch (ClarificationException e) {
            log.info("Update {}: esclarecimento solicitado ao usuário", update.updateId());
            telegramClient.sendMessage(chatId, e.getMessage());
        } catch (OpenAiException e) {
            log.warn("Falha da IA ao processar update {}: {}", update.updateId(), e.getMessage());
            telegramClient.sendMessage(chatId, ERRO_GENERICO);
        } catch (Throwable t) {
            // Throwable (e não Exception): NoClassDefFoundError e afins também devem
            // virar resposta amigável em vez de derrubar o loop do polling.
            log.error("Erro inesperado ao processar update {}: {}", update.updateId(), t.toString(), t);
            telegramClient.sendMessage(chatId, ERRO_GENERICO);
        }
    }

    private Expense buildExpense(TelegramMessage message, TelegramAudio audio, long updateId) {
        User user = userService.upsert(message.from(), message.chat().id());

        byte[] bytes = downloadAudio(audio);
        String transcript = openAiClient.transcribe(bytes, audio.fileId() + ".ogg");
        LocalDate today = dateResolutionService.today();
        ExpenseExtraction extraction = openAiClient.extractExpense(transcript, today);

        if (extraction.clarificationRequired()
                && extraction.clarificationQuestion() != null
                && !extraction.clarificationQuestion().isBlank()) {
            throw new ClarificationException(extraction.clarificationQuestion().trim());
        }
        if (extraction.amount() == null || extraction.amount().signum() <= 0) {
            throw new ClarificationException("Qual foi o valor da despesa? Mande outro áudio dizendo o valor, por favor. 💰");
        }
        if (extraction.description() == null || extraction.description().isBlank()) {
            throw new ClarificationException("Não entendi a descrição da despesa. Pode mandar outro áudio descrevendo o que você comprou? 📝");
        }

        Expense expense = new Expense();
        expense.setUser(user);
        expense.setAmount(extraction.amount().setScale(2, RoundingMode.HALF_UP));
        expense.setDescription(truncate(extraction.description(), 200));
        expense.setCategory(truncate(
                extraction.category() == null || extraction.category().isBlank() ? "Outros" : extraction.category(), 50));
        expense.setMerchant(capitalize(extraction.merchant()));
        expense.setPaymentMethod(normalizePaymentMethod(extraction.paymentMethod()));
        expense.setExpenseDate(resolveDate(extraction.expenseDate(), transcript));
        expense.setSource("TELEGRAM_AUDIO");
        expense.setTelegramUpdateId(updateId);
        expense.setStatus("CONFIRMED");
        return expense;
    }

    private LocalDate resolveDate(String fromAi, String transcript) {
        LocalDate parsed = null;
        if (fromAi != null && !fromAi.isBlank()) {
            try {
                parsed = LocalDate.parse(fromAi.trim());
            } catch (DateTimeParseException e) {
                log.warn("Data inválida retornada pela IA: {}", fromAi);
            }
        }
        return dateResolutionService.resolve(parsed, transcript);
    }

    /**
     * O Telegram exige duas chamadas: getFile(fileId) devolve o file_path e
     * downloadFile(filePath) baixa os bytes. O fileId NÃO é um caminho válido.
     */
    private byte[] downloadAudio(TelegramAudio audio) {
        TelegramFile file = telegramClient.getFile(audio.fileId())
                .orElseThrow(() -> new OpenAiException("Não foi possível obter o áudio do Telegram"));
        if (file.filePath() == null || file.filePath().isBlank()) {
            throw new OpenAiException("Telegram não retornou caminho para o áudio");
        }
        return telegramClient.downloadFile(file.filePath())
                .orElseThrow(() -> new OpenAiException("Não foi possível baixar o áudio do Telegram"));
    }

    private String confirmation(Expense expense) {
        StringBuilder sb = new StringBuilder("✅ Despesa registrada!\n");
        sb.append("💰 R$ ").append(formatAmount(expense.getAmount())).append("\n");
        sb.append("📝 ").append(expense.getDescription()).append("\n");
        sb.append("🏷 ").append(expense.getCategory());
        if (expense.getMerchant() != null) {
            sb.append(" — ").append(expense.getMerchant());
        }
        sb.append("\n");
        if (expense.getPaymentMethod() != null) {
            sb.append("💳 ").append(expense.getPaymentMethod()).append("\n");
        }
        sb.append("📅 ").append(formatDate(expense.getExpenseDate()));
        return sb.toString();
    }

    static String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    static String formatDate(LocalDate date) {
        return String.format(Locale.ROOT, "%02d/%02d/%04d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    /**
     * Capitaliza a inicial de cada palavra do merchant. O modelo devolve o nome
     * como transcreveu — em caixa baixa ("mercado pao de acucar", "farmácia
     * pacheco") — e gravar assim polui relatórios por estabelecimento.
     */
    private String capitalize(String merchant) {
        String clean = truncate(merchant, 100);
        if (clean == null || clean.isBlank()) {
            return clean;
        }
        StringBuilder sb = new StringBuilder(clean.length());
        boolean inicioPalavra = true;
        for (char c : clean.toCharArray()) {
            sb.append(inicioPalavra ? Character.toUpperCase(c) : c);
            inicioPalavra = !Character.isLetterOrDigit(c);
        }
        return sb.toString();
    }

    /**
     * Normaliza o meio de pagamento para um vocabulário estável, para que
     * "débito", "debito" e "no débito" virem todos "Débito" — senão relatórios
     * por cartão separaram o mesmo meio em várias linhas.
     */
    private String normalizePaymentMethod(String raw) {
        String clean = truncate(raw, 50);
        if (clean == null || clean.isBlank()) {
            return clean;
        }
        String lower = clean.toLowerCase();
        if (lower.contains("débito") || lower.contains("debito")) {
            return "Débito";
        }
        if (lower.contains("crédito") || lower.contains("credito")) {
            return "Crédito";
        }
        if (lower.contains("pix")) {
            return "Pix";
        }
        if (lower.contains("dinheiro") || lower.contains("especie") || lower.contains("espécie")) {
            return "Dinheiro";
        }
        // Marcas/bancos (BTG, Nubank, Itaú...) ficam como o modelo devolveu, capitalizados.
        return capitalize(clean);
    }

    /** Sinaliza que o bot deve perguntar algo ao usuário sem salvar a despesa. */
    private static class ClarificationException extends RuntimeException {
        ClarificationException(String message) {
            super(message);
        }
    }
}
