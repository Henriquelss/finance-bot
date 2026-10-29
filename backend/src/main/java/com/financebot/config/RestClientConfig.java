package com.financebot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Bean compartilhado de RestClient. O auto-config do Spring Boot 4 nem sempre
 * expõe um builder pronto; criar explicitamente mantém tudo previsível.
 *
 * <p>Os timeouts não são opcionais: o polling roda em uma única thread, então uma
 * chamada pendurada (Telegram ou OpenAI) sem timeout pararia o bot por completo.
 */
@Configuration
public class RestClientConfig {

    /** Tempo de conexão com a API. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Tempo de espera pela resposta; acima do timeout máximo do long polling (50 s). */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(90);

    @Bean
    RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
