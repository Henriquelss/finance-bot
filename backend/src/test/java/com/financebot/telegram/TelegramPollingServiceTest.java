package com.financebot.telegram;

import com.financebot.config.OpenAiProperties;
import com.financebot.config.TelegramProperties;
import com.financebot.dto.TelegramAudio;
import com.financebot.dto.TelegramChat;
import com.financebot.dto.TelegramMessage;
import com.financebot.dto.TelegramUpdate;
import com.financebot.service.ExpenseProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Garante as garantias do loop de polling: o offset só avança depois do
 * processamento e nenhuma falha — nem {@code Error} — derruba o scheduler.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TelegramPollingServiceTest {

    private static final long CHAT_ID = 555L;

    @Mock
    private TelegramClient telegramClient;
    @Mock
    private ExpenseProcessingService expenseProcessingService;

    private TelegramPollingService service;

    @BeforeEach
    void setUp() {
        TelegramProperties properties = new TelegramProperties();
        properties.setToken("token-teste");
        properties.setHeartbeatLogSeconds(0); // heartbeat desativado para não poluir o log do teste
        service = new TelegramPollingService(
                properties, new OpenAiProperties(), telegramClient, expenseProcessingService);
    }

    @Test
    void naoDeveProcessarPendenciasAnterioresAoBoot() {
        when(telegramClient.getUpdates(anyLong(), anyInt()))
                .thenReturn(ok(update(100L)), ok());

        service.poll();
        service.poll();

        verify(expenseProcessingService, never()).process(any());
    }

    @Test
    void deveProcessarUpdateRecenteRecebidoNoPrimeiroPoll() {
        // Regressão real: um restart às 16:55 e uma mensagem às 16:58 era tratada
        // como "pendência antiga" e descartada em silêncio.
        when(telegramClient.getUpdates(anyLong(), anyInt()))
                .thenReturn(ok(updateRecente(50L)), ok());

        service.poll();

        ArgumentCaptor<TelegramUpdate> captor = ArgumentCaptor.forClass(TelegramUpdate.class);
        verify(expenseProcessingService).process(captor.capture());
        assertThat(captor.getValue().updateId()).isEqualTo(50L);
    }

    @Test
    void deveProcessarUpdatesRecebidosAposOPrimeiroPoll() {
        when(telegramClient.getUpdates(anyLong(), anyInt()))
                .thenReturn(ok(update(100L)), ok(update(101L)));

        service.poll();
        service.poll();

        ArgumentCaptor<TelegramUpdate> captor = ArgumentCaptor.forClass(TelegramUpdate.class);
        verify(expenseProcessingService).process(captor.capture());
        assertThat(captor.getValue().updateId()).isEqualTo(101L);
    }

    @Test
    void deveAvancarOffsetAposProcessarParaONextPoll() {
        when(telegramClient.getUpdates(anyLong(), anyInt()))
                .thenReturn(ok(update(100L)), ok(update(105L)), ok());

        service.poll(); // descarta o 100 (backlog do boot) -> offset 101
        service.poll(); // processa o 105                -> offset 106
        service.poll(); // confirma que o próximo getUpdates já parte de 106

        ArgumentCaptor<Long> offsets = ArgumentCaptor.forClass(Long.class);
        verify(telegramClient, times(3)).getUpdates(offsets.capture(), anyInt());
        assertThat(offsets.getAllValues()).containsExactly(0L, 101L, 106L);
    }

    @Test
    void deveManterOffsetQuandoGetUpdatesFalha() {
        when(telegramClient.getUpdates(anyLong(), anyInt()))
                .thenReturn(falha(), ok(update(5L)), ok());

        service.poll(); // falha: offset não avança
        service.poll(); // primeiro poll de verdade, descarta o update 5
        service.poll();

        ArgumentCaptor<Long> offsets = ArgumentCaptor.forClass(Long.class);
        verify(telegramClient, times(3)).getUpdates(offsets.capture(), anyInt());
        assertThat(offsets.getAllValues()).containsExactly(0L, 0L, 6L);
        verify(expenseProcessingService, never()).process(any());
    }

    @Test
    void naoDeveMorrerQuandoProcessamentoLancaError() {
        // Reproduz a classe de bug que derrubava o bot: NoClassDefFoundError é Error,
        // não Exception, e escapava dos catch (Exception) antigos.
        when(telegramClient.getUpdates(anyLong(), anyInt()))
                .thenReturn(ok(update(7L)), ok(update(9L)), ok());
        doThrow(new NoClassDefFoundError("org/reactivestreams/Publisher"))
                .when(expenseProcessingService).process(any());

        assertThatCode(() -> {
            service.poll(); // descarta o 7 (backlog do boot)
            service.poll(); // processa o 9 e falha
            service.poll(); // segue rodando
        }).doesNotThrowAnyException();

        // O update que falhou não trava o loop: o offset avança e a rodada seguinte segue limpa
        ArgumentCaptor<Long> offsets = ArgumentCaptor.forClass(Long.class);
        verify(telegramClient, times(3)).getUpdates(offsets.capture(), anyInt());
        assertThat(offsets.getAllValues()).containsExactly(0L, 8L, 10L);
        verify(expenseProcessingService, times(1)).process(any());
    }

    @Test
    void naoDeveLerNadaQuandoTokenEstaAusente() {
        service = new TelegramPollingService(
                new TelegramProperties(), new OpenAiProperties(), telegramClient, expenseProcessingService);

        service.poll();

        verify(telegramClient, never()).getUpdates(anyLong(), anyInt());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static TelegramClient.PollingResult ok(TelegramUpdate... updates) {
        return new TelegramClient.PollingResult(List.of(updates), true);
    }

    private static TelegramClient.PollingResult falha() {
        return new TelegramClient.PollingResult(List.of(), false);
    }

    /** Update com data antiga (pendência de antes do boot). */
    private static TelegramUpdate update(long updateId) {
        return update(updateId, Instant.now().minus(Duration.ofDays(2)).getEpochSecond());
    }

    /** Update enviado agora — não pode ser tratado como pendência antiga. */
    private static TelegramUpdate updateRecente(long updateId) {
        return update(updateId, Instant.now().getEpochSecond());
    }

    private static TelegramUpdate update(long updateId, long date) {
        TelegramAudio audio = new TelegramAudio("audio-1", "uniq", 1024, 5);
        return new TelegramUpdate(updateId, new TelegramMessage(
                1, null, new TelegramChat(CHAT_ID), date, audio, null));
    }
}
