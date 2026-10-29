package com.financebot.telegram;

import com.financebot.config.OpenAiProperties;
import com.financebot.config.TelegramProperties;
import com.financebot.dto.TelegramMessage;
import com.financebot.dto.TelegramUpdate;
import com.financebot.service.ExpenseProcessingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Long polling da Bot API do Telegram. Enquanto a aplicação estiver rodando
 * (ex.: pelo Docker Compose), o bot recebe mensagens em tempo quase real,
 * sem necessidade de IP público ou webhook.
 *
 * <p>Três garantias importantes do loop:
 * <ol>
 *   <li>O {@code offset} só avança <b>depois</b> de o update ser processado, então
 *       uma queda no meio do processamento faz o Telegram reentregar a mensagem em
 *       vez de consumi-la em silêncio.</li>
 *   <li>{@code catch (Throwable)} — falhas que são {@code Error} (como o
 *       {@code NoClassDefFoundError} que quebrava a transcrição) não podem derrubar
 *       o scheduler e deixar o bot mudo.</li>
 *   <li>Heartbeat: o polling registra atividade sem inundar o log (uma linha por
 *       intervalo configurado, e sempre que houver mensagem nova), e avisa quando
 *       fica sem sucesso por várias rodadas seguidas.</li>
 * </ol>
 */
@Component
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    /**
     * Idade máxima de uma pendência do Telegram para ainda ser processada após o
     * boot. Evita registrar despesas de áudios acumulados durante o tempo em que
     * o bot ficou parado, sem comer mensagens enviadas logo depois de subir.
     */
    private static final Duration BACKLOG_MAX_AGE = Duration.ofHours(24);

    private final TelegramProperties telegramProperties;
    private final OpenAiProperties openAiProperties;
    private final TelegramClient telegramClient;
    private final ExpenseProcessingService expenseProcessingService;

    private final AtomicLong offset = new AtomicLong(0);
    private final AtomicBoolean firstPoll = new AtomicBoolean(true);

    private final AtomicLong totalPolls = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    private volatile Instant lastSuccessAt;
    private volatile Instant lastHeartbeatAt;

    public TelegramPollingService(TelegramProperties telegramProperties,
                                  OpenAiProperties openAiProperties,
                                  TelegramClient telegramClient,
                                  ExpenseProcessingService expenseProcessingService) {
        this.telegramProperties = telegramProperties;
        this.openAiProperties = openAiProperties;
        this.telegramClient = telegramClient;
        this.expenseProcessingService = expenseProcessingService;
    }

    @PostConstruct
    void logStatus() {
        if (telegramProperties.isEnabled()) {
            log.info("Telegram polling habilitado (intervalo {} ms, timeout {} s)",
                    telegramProperties.getPollingIntervalMs(), telegramProperties.getPollingTimeoutSeconds());
        } else {
            log.warn("TELEGRAM_BOT_TOKEN ausente — polling do bot desativado");
        }
        if (!openAiProperties.isEnabled()) {
            log.warn("Chave da IA ausente (AI_API_KEY) — transcrição/interpretação indisponível");
        } else {
            log.info("Provedor de IA: {} | transcrição={} | extração={}",
                    openAiProperties.getBaseUrl(),
                    openAiProperties.getTranscriptionModel(),
                    openAiProperties.getExtractionModel());
        }
    }

    @Scheduled(fixedDelayString = "${telegram.polling-interval-ms:2000}")
    public void poll() {
        if (!telegramProperties.isEnabled()) {
            return;
        }
        Instant agora = Instant.now();

        TelegramClient.PollingResult resultado;
        try {
            resultado = telegramClient.getUpdates(offset.get(), telegramProperties.getPollingTimeoutSeconds());
        } catch (Throwable t) {
            registrarFalha(agora, t);
            return;
        }
        if (!resultado.ok()) {
            registrarFalha(agora, null);
            return;
        }
        registrarSucesso(agora);

        List<TelegramUpdate> updates = resultado.updates();
        for (TelegramUpdate update : updates) {
            long proximoOffset = update.updateId() + 1;
            if (firstPoll.get() && pendenciaAntiga(update, agora)) {
                // Descarta pendências de antes do boot para não registrar despesas de
                // áudios antigos. Mensagens recentes NÃO são descartadas: sem isso,
                // todo restart comia a primeira mensagem enviada logo depois dele.
                log.info("Update {} descartado: é pendência de antes do boot",
                        update.updateId());
                offset.set(proximoOffset);
                continue;
            }
            try {
                expenseProcessingService.process(update);
            } catch (Throwable t) {
                log.error("Erro inesperado ao processar update {}: {}", update.updateId(), t.toString(), t);
            } finally {
                // Avança no fim: se a JVM cair no meio, o Telegram reentrega o update
                offset.set(Math.max(offset.get(), proximoOffset));
            }
        }
        if (!updates.isEmpty()) {
            firstPoll.set(false);
        }
        heartbeat(agora, updates.size());
    }

    /**
     * Uma pendência só é descartada se for realmente antiga. {@code message.date}
     * vem em epoch seconds do Telegram; sem ele, assume-se que é antiga.
     */
    private boolean pendenciaAntiga(TelegramUpdate update, Instant agora) {
        TelegramMessage message = update.message();
        if (message == null || message.date() <= 0) {
            return true;
        }
        return Instant.ofEpochSecond(message.date()).isBefore(agora.minus(BACKLOG_MAX_AGE));
    }

    /** Marca que o polling está saudável e limpa o contador de falhas. */
    private void registrarSucesso(Instant agora) {
        totalPolls.incrementAndGet();
        lastSuccessAt = agora;
        long falhas = consecutiveFailures.getAndUpdate(atual -> 0);
        if (falhas > 0) {
            log.info("Polling recuperado após {} falha(s) consecutiva(s) no getUpdates", falhas);
        }
    }

    /**
     * Contabiliza falhas seguidas de getUpdates. Só alerta quando elas se repetem,
     * para não poluir o log com uma linha por tentativa (uma a cada 2 s).
     */
    private void registrarFalha(Instant agora, Throwable causa) {
        long falhas = consecutiveFailures.incrementAndGet();
        int aviso = telegramProperties.getFailedPollsWarnThreshold();
        if (causa != null) {
            log.error("Falha inesperada no polling: {}", causa.toString(), causa);
        }
        if (falhas == aviso) {
            log.warn("Polling sem sucesso há {} chamadas seguidas ao getUpdates — "
                    + "verifique conectividade, token ou se outro processo está usando o mesmo bot", falhas);
        }
        heartbeat(agora, 0);
    }

    /**
     * Escreve no log quando há atividade nova ou quando venceu o intervalo de
     * heartbeat — nunca a cada rodada (seriam dezenas de milhares de linhas por dia).
     */
    private void heartbeat(Instant agora, int updatesRecebidos) {
        long intervalo = telegramProperties.getHeartbeatLogSeconds();
        if (intervalo <= 0) {
            return;
        }
        boolean primeiraVez = lastHeartbeatAt == null;
        boolean intervaloVencido = !primeiraVez
                && Duration.between(lastHeartbeatAt, agora).getSeconds() >= intervalo;
        if (!primeiraVez && !intervaloVencido && updatesRecebidos == 0) {
            return;
        }
        lastHeartbeatAt = agora;
        log.info("Polling ativo | polls={} | falhas={} | offset={} | última mensagem={}",
                totalPolls.get(),
                consecutiveFailures.get(),
                offset.get(),
                lastSuccessAt == null ? "nunca"
                        : LocalTime.ofInstant(lastSuccessAt, ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }
}
