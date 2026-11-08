package me.wani4ka.concurrency.review.marketplace;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketplaceClientTests {

    @Test
    void timesOutWhenMarketplaceNeverResponds() {
        var client = new MarketplaceClient(
                WebClient.builder().exchangeFunction(request -> Mono.never()), Duration.ofMillis(50));

        assertThatThrownBy(() -> client.fetch(164))
                .isInstanceOf(MarketplaceException.class)
                .hasCauseInstanceOf(TimeoutException.class)
                .hasMessage("JetBrains Marketplace request timed out");
    }
}
