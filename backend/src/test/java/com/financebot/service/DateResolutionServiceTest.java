package com.financebot.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DateResolutionServiceTest {

    // 2026-10-16 12:00 em America/Sao_Paulo (UTC-3) = 2026-10-16 15:00 UTC
    private static final Instant INSTANTE = Instant.parse("2026-10-16T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private DateResolutionService service() {
        return new DateResolutionService(Clock.fixed(INSTANTE, FUSO));
    }

    @Test
    void deveUsarDataLocalDeHojeQuandoNenhumaDataInformada() {
        LocalDate resultado = service().resolve(null, "gastei cem reais no restaurante japonês");

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void deveResolverOntem() {
        LocalDate resultado = service().resolve(null, "Ontem gastei 80 reais no mercado");

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void deveResolverOntemSemAcentoEComMaiusculas() {
        LocalDate resultado = service().resolve(null, "ONTEM gastei 30 no café");

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void deveResolverAnteontem() {
        LocalDate resultado = service().resolve(null, "gastei 45 reais na anteontem no cinema");

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void devePriorizarDataInterpretadaPelaIa() {
        LocalDate resultado = service().resolve(LocalDate.of(2026, 10, 10), "ontem gastei 20");

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void deveUsarFusoDeSaoPauloParaHoje() {
        // 2026-10-17 01:00 UTC ainda é dia 16 (22:00) em São Paulo — o fuso local prevalece
        Instant madrugada = Instant.parse("2026-10-17T01:00:00Z");
        DateResolutionService service = new DateResolutionService(Clock.fixed(madrugada, FUSO));

        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 10, 16));
    }
}
