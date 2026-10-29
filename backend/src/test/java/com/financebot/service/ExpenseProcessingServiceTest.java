package com.financebot.service;

import com.financebot.ai.OpenAiClient;
import com.financebot.ai.OpenAiException;
import com.financebot.config.TelegramProperties;
import com.financebot.dto.ExpenseExtraction;
import com.financebot.dto.TelegramAudio;
import com.financebot.dto.TelegramChat;
import com.financebot.dto.TelegramFile;
import com.financebot.dto.TelegramMessage;
import com.financebot.dto.TelegramUpdate;
import com.financebot.dto.TelegramUser;
import com.financebot.entity.Expense;
import com.financebot.entity.User;
import com.financebot.repository.ExpenseRepository;
import com.financebot.telegram.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseProcessingServiceTest {

    private static final Instant INSTANTE = Instant.parse("2026-10-16T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final long CHAT_ID = 555L;
    private static final long USER_ID = 42L;
    private static final long UPDATE_ID = 9001L;

    @Mock
    private TelegramClient telegramClient;
    @Mock
    private OpenAiClient openAiClient;
    @Mock
    private UserService userService;
    @Mock
    private ExpenseRepository expenseRepository;

    private ExpenseProcessingService service;

    @BeforeEach
    void setUp() {
        DateResolutionService dateResolutionService =
                new DateResolutionService(Clock.fixed(INSTANTE, FUSO));
        service = new ExpenseProcessingService(
                telegramClient, openAiClient, userService, dateResolutionService,
                expenseRepository, new TelegramProperties());
    }

    @Test
    void deveSalvarDespesaEConfirmarNoFluxoFeliz() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        mockDownloadAudio();
        when(openAiClient.transcribe(any(), anyString())).thenReturn("gastei cem reais no restaurante japonês e paguei com o BTG");
        when(openAiClient.extractExpense(anyString(), any())).thenReturn(extraction(
                new BigDecimal("100"), "Restaurante japonês", "Alimentação", "BTG",
                "Sushi Yama", "2026-10-16", false, null));

        service.process(audioUpdate());

        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).saveAndFlush(captor.capture());
        Expense salva = captor.getValue();
        assertThat(salva.getAmount()).isEqualByComparingTo("100.00");
        assertThat(salva.getDescription()).isEqualTo("Restaurante japonês");
        assertThat(salva.getCategory()).isEqualTo("Alimentação");
        assertThat(salva.getPaymentMethod()).isEqualTo("BTG");
        assertThat(salva.getMerchant()).isEqualTo("Sushi Yama");
        assertThat(salva.getExpenseDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(salva.getSource()).isEqualTo("TELEGRAM_AUDIO");
        assertThat(salva.getStatus()).isEqualTo("CONFIRMED");
        assertThat(salva.getTelegramUpdateId()).isEqualTo(UPDATE_ID);

        verify(telegramClient).sendMessage(eq(CHAT_ID), contains("R$ 100,00"));
    }

    @Test
    void deveRegistrarOntemQuandoTranscricaoMencionaOntem() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        mockDownloadAudio();
        when(openAiClient.transcribe(any(), anyString())).thenReturn("ontem gastei 80 reais no mercado");
        when(openAiClient.extractExpense(anyString(), any())).thenReturn(extraction(
                new BigDecimal("80"), "Mercado", "Mercado", null,
                null, null, false, null));

        service.process(audioUpdate());

        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExpenseDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void deveNormalizarMeioDePagamentoDerivadoDoCartao() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        mockDownloadAudio();
        when(openAiClient.transcribe(any(), anyString())).thenReturn("cinquenta reais no starbucks no debito");
        when(openAiClient.extractExpense(anyString(), any())).thenReturn(extraction(
                new BigDecimal("50"), "Café no Starbucks", "Alimentação", "debito",
                "mercado pao de acucar", null, false, null));

        service.process(audioUpdate());

        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).saveAndFlush(captor.capture());
        Expense salva = captor.getValue();
        assertThat(salva.getPaymentMethod()).isEqualTo("Débito");
        assertThat(salva.getMerchant()).isEqualTo("Mercado Pao De Acucar");
    }

    @Test
    void devePerguntarQuandoIaSinalizaClarification() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        mockDownloadAudio();
        when(openAiClient.transcribe(any(), anyString())).thenReturn("gastei no restaurante");
        when(openAiClient.extractExpense(anyString(), any())).thenReturn(extraction(
                null, "Restaurante", null, null,
                null, null, true, "Qual foi o valor da despesa?"));

        service.process(audioUpdate());

        verify(expenseRepository, never()).saveAndFlush(any());
        verify(telegramClient).sendMessage(eq(CHAT_ID), contains("Qual foi o valor da despesa?"));
    }

    @Test
    void devePerguntarValorQuandoAusenteSemClarificationDaIa() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        mockDownloadAudio();
        when(openAiClient.transcribe(any(), anyString())).thenReturn("gastei no restaurante");
        when(openAiClient.extractExpense(anyString(), any())).thenReturn(extraction(
                null, "Restaurante", null, null, null, null, false, null));

        service.process(audioUpdate());

        verify(expenseRepository, never()).saveAndFlush(any());
        verify(telegramClient).sendMessage(eq(CHAT_ID), contains("valor"));
    }

    @Test
    void deveIgnorarUpdateJaProcessado() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(true);

        service.process(audioUpdate());

        verify(openAiClient, never()).transcribe(any(), anyString());
        verify(expenseRepository, never()).saveAndFlush(any());
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void devePedirAudioQuandoMensagemNaoTemAudio() {
        TelegramUpdate update = new TelegramUpdate(UPDATE_ID,
                new TelegramMessage(1, new TelegramUser(USER_ID, "Henrique"), new TelegramChat(CHAT_ID), 0, null, null));

        service.process(update);

        verify(telegramClient).sendMessage(eq(CHAT_ID), contains("áudio"));
        verify(expenseRepository, never()).saveAndFlush(any());
    }

    @Test
    void deveResponderAmigavelmenteQuandoOpenAiFalha() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        mockDownloadAudio();
        when(openAiClient.transcribe(any(), anyString())).thenThrow(new OpenAiException("Falha ao transcrever"));

        service.process(audioUpdate());

        verify(expenseRepository, never()).saveAndFlush(any());
        verify(telegramClient).sendMessage(eq(CHAT_ID), contains("problema"));
    }

    @Test
    void deveResponderAmigavelmenteQuandoGetFileFalha() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        when(telegramClient.getFile("audio-1")).thenReturn(Optional.empty());

        service.process(audioUpdate());

        verify(expenseRepository, never()).saveAndFlush(any());
        verify(telegramClient).sendMessage(eq(CHAT_ID), contains("problema"));
    }

    @Test
    void deveResponderAmigavelmenteQuandoDownloadFalha() {
        when(expenseRepository.existsByTelegramUpdateId(UPDATE_ID)).thenReturn(false);
        when(userService.upsert(any(), eq(CHAT_ID))).thenReturn(user());
        when(telegramClient.getFile("audio-1")).thenReturn(Optional.of(new TelegramFile("audio-1", "voice/file_1.oga", 1024)));
        when(telegramClient.downloadFile("voice/file_1.oga")).thenReturn(Optional.empty());

        service.process(audioUpdate());

        verify(expenseRepository, never()).saveAndFlush(any());
        verify(telegramClient).sendMessage(eq(CHAT_ID), contains("problema"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TelegramUpdate audioUpdate() {
        TelegramAudio audio = new TelegramAudio("audio-1", "uniq", 1024, 5);
        return new TelegramUpdate(UPDATE_ID,
                new TelegramMessage(1, new TelegramUser(USER_ID, "Henrique"), new TelegramChat(CHAT_ID), 0, audio, null));
    }

    /** Simula o fluxo real do Telegram: getFile(fileId) → downloadFile(filePath). */
    private void mockDownloadAudio() {
        when(telegramClient.getFile("audio-1"))
                .thenReturn(Optional.of(new TelegramFile("audio-1", "voice/file_1.oga", 1024)));
        when(telegramClient.downloadFile("voice/file_1.oga")).thenReturn(Optional.of(new byte[]{1}));
    }

    private User user() {
        User user = new User();
        user.setTelegramUserId(USER_ID);
        user.setTelegramChatId(CHAT_ID);
        return user;
    }

    private ExpenseExtraction extraction(BigDecimal amount, String description, String category,
                                         String paymentMethod, String merchant, String expenseDate,
                                         boolean needsClarification, String clarificationQuestion) {
        return new ExpenseExtraction(amount, description, category, paymentMethod, merchant,
                expenseDate, needsClarification, clarificationQuestion);
    }
}
