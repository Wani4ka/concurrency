package me.wani4ka.concurrency.review;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
public class MarketplaceClient {

    private final WebClient client;
    private final Duration timeout;

    @Autowired
    public MarketplaceClient(WebClient.Builder builder) {
        this(builder, Duration.ofSeconds(10));
    }

    MarketplaceClient(WebClient.Builder builder, Duration timeout) {
        this.client = builder.baseUrl("https://plugins.jetbrains.com").build();
        this.timeout = timeout;
    }

    List<MarketplaceReviewDto> fetch(int pluginId) {
        // shortcut: read the first page until collecting older reviews is required.
        return client.get()
                .uri("/api/plugins/{id}/comments?page=1&size=20", pluginId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), ClientResponse::createException)
                .bodyToMono(new ParameterizedTypeReference<List<MarketplaceReviewDto>>() {})
                .switchIfEmpty(Mono.error(new IllegalStateException("Empty Marketplace response")))
                .timeout(timeout)
                .onErrorMap(error -> new MarketplaceException(
                        error instanceof TimeoutException
                                ? "JetBrains Marketplace request timed out"
                                : "Could not load JetBrains Marketplace reviews",
                        error))
                .block();
    }
}
