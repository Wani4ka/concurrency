package me.wani4ka.concurrency.review.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.ClientRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReviewControllerTests.StubConfiguration.class)
class ReviewControllerTests {

    @TempDir
    static Path reviewsDirectory;

    @DynamicPropertySource
    static void reviewStorage(DynamicPropertyRegistry registry) {
        registry.add("reviews.directory", () -> reviewsDirectory.toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MarketplaceStub stub;

    @BeforeEach
    void resetStub() throws IOException {
        Files.deleteIfExists(reviewsDirectory.resolve("164.json"));
        stub.status.set(200);
        stub.requests.set(0);
        stub.payload = stub.originalPayload;
    }

    @Test
    void collectsAndReturnsParsedReviewsWithoutDuplicates() throws Exception {
        submit("https://plugins.jetbrains.com/plugin/164-ideavim");
        awaitStatus("SUCCEEDED");
        stub.payload = stub.readFixture("comments-updated.json");
        submit("https://plugins.jetbrains.com/plugin/164-ideavim/reviews");
        awaitStatus("SUCCEEDED");

        mvc.perform(get("/answer").param("pluginId", "164"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.requestedAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.completedAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.reviews.length()").value(2))
                .andExpect(jsonPath("$.reviews[0].id").value(42))
                .andExpect(jsonPath("$.reviews[0].text").value("Works better & fast."))
                .andExpect(jsonPath("$.reviews[0].rating").value(4))
                .andExpect(jsonPath("$.reviews[0].author").value("Demo User"))
                .andExpect(jsonPath("$.reviews[0].publishedAt").value("2026-10-29T18:00:10Z"))
                .andExpect(jsonPath("$.reviews[1].id").value(44));
        assertThat(stub.requests.get()).isEqualTo(2);
        assertThat(reviewsDirectory.resolve("164.json")).exists();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://plugins.jetbrains.com/plugin/164-ideavim",
            "https://plugins.jetbrains.com.example.org/plugin/164-ideavim",
            "https://someone@plugins.jetbrains.com/plugin/164-ideavim",
            "https://plugins.jetbrains.com:8080/plugin/164-ideavim",
            "https://plugins.jetbrains.com/plugin/ideavim",
            "https://plugins.jetbrains.com/plugin/164-ideavim/versions"
    })
    void rejectsUnsupportedUrlsBeforeFetching(String url) throws Exception {
        mvc.perform(post("/post").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Expected a JetBrains Marketplace plugin URL"));
        assertThat(stub.requests.get()).isZero();
    }

    @Test
    void explainsWhyZeroPluginIdIsInvalid() throws Exception {
        mvc.perform(get("/answer").param("pluginId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("pluginId must be positive"))
                .andExpect(jsonPath("$.instance").value("/answer"));
    }

    @Test
    void keepsSavedReviewsAfterAnUpstreamError() throws Exception {
        submit("https://plugins.jetbrains.com/plugin/164-ideavim");
        awaitStatus("SUCCEEDED");
        stub.status.set(503);

        submit("https://plugins.jetbrains.com/plugin/164-ideavim");
        awaitStatus("FAILED");
        mvc.perform(get("/answer").param("pluginId", "164"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.reviews[0].id").value(42));

        stub.status.set(200);
        submit("https://plugins.jetbrains.com/plugin/164-ideavim");
        awaitStatus("SUCCEEDED");
        assertThat(stub.requests.get()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{}]",
            "[{\"id\":42,\"rating\":4,\"author\":{\"name\":\"Demo User\"},\"cdate\":\"1793296810000\"}]",
            "[{\"id\":42,\"rating\":4.5,\"comment\":\"Useful\",\"author\":{\"name\":\"Demo User\"},\"cdate\":\"1793296810000\"}]"
    })
    void reportsMalformedUpstreamResponseAsFailed(String response) throws Exception {
        stub.payload = response.getBytes(StandardCharsets.UTF_8);

        submit("https://plugins.jetbrains.com/plugin/164-ideavim");
        awaitStatus("FAILED");
    }

    @Test
    void doesNotFollowMarketplaceRedirects() throws Exception {
        stub.status.set(302);

        submit("https://plugins.jetbrains.com/plugin/164-ideavim");
        awaitStatus("FAILED");
        assertThat(stub.requests.get()).isEqualTo(1);
    }

    private void submit(String url) throws Exception {
        mvc.perform(post("/post").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pluginId").value(164));
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/answer").param("pluginId", "164"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(expected)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        MarketplaceStub marketplaceStub() throws IOException {
            return new MarketplaceStub();
        }

        @Bean
        WebClientCustomizer marketplaceStubClient(MarketplaceStub stub) {
            return builder -> builder.filter((request, next) -> {
                URI url = request.url();
                URI localUrl = URI.create("http://127.0.0.1:" + stub.server.getAddress().getPort()
                        + url.getRawPath() + "?" + url.getRawQuery());
                return next.exchange(ClientRequest.from(request).url(localUrl).build());
            });
        }
    }

    static class MarketplaceStub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger status = new AtomicInteger(200);
        private final AtomicInteger requests = new AtomicInteger();
        private final byte[] originalPayload;
        private volatile byte[] payload;

        MarketplaceStub() throws IOException {
            originalPayload = readFixture("comments.json");
            payload = originalPayload;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    if (!"GET".equals(exchange.getRequestMethod())
                            || !"/api/plugins/164/comments?page=1&size=20".equals(exchange.getRequestURI().toString())) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    int responseStatus = status.get();
                    byte[] body = payload;
                    if (responseStatus == 302) {
                        exchange.getResponseHeaders().set("Location",
                                "http://localhost:" + server.getAddress().getPort() + "/redirected");
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(responseStatus, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        byte[] readFixture(String name) throws IOException {
            try (var fixture = getClass().getResourceAsStream("/marketplace/" + name)) {
                return fixture.readAllBytes();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
