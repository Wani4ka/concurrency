package me.wani4ka.concurrency.review;

import me.wani4ka.concurrency.web.ReviewController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketplaceClientTests {

    @Test
    void reportsGatewayTimeoutWhenMarketplaceNeverResponds() throws Exception {
        var client = new MarketplaceClient(
                WebClient.builder().exchangeFunction(request -> Mono.never()), Duration.ofMillis(50));
        var controller = new ReviewController(new ReviewService(client, new MarketplaceReviewParser()));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/post").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://plugins.jetbrains.com/plugin/164-ideavim\"}"))
                .andExpect(status().isGatewayTimeout());
    }
}
