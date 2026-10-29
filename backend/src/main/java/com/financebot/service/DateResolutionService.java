package com.financebot.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Regra de datas do PRD: quando nenhuma data é informada, usa a data local de
 * recebimento em America/Sao_Paulo. "Ontem"/"anteontem" são resolvidos
 * relativos à data local de hoje.
 */
@Component
public class DateResolutionService {

    private final Clock clock;

    @Autowired
    public DateResolutionService(@Value("${app.timezone:America/Sao_Paulo}") String timezone) {
        this(Clock.system(ZoneId.of(timezone)));
    }

    DateResolutionService(Clock clock) {
        this.clock = clock;
    }

    /** Data local de hoje no fuso de negócio. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Resolve a data da despesa.
     *
     * @param fromAi     data interpretada pela IA, ou null se ausente/inválida
     * @param transcript transcrição original, usada como fallback para "ontem"/"anteontem"
     */
    public LocalDate resolve(LocalDate fromAi, String transcript) {
        if (fromAi != null) {
            return fromAi;
        }
        String normalized = normalize(transcript);
        if (normalized.contains("anteontem")) {
            return today().minusDays(2);
        }
        if (normalized.contains("ontem")) {
            return today().minusDays(1);
        }
        return today();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer
                .normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
