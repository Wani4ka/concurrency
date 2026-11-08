package me.wani4ka.concurrency.review;

import me.wani4ka.concurrency.review.marketplace.MarketplaceClient;
import me.wani4ka.concurrency.review.marketplace.MarketplaceReviewParser;
import me.wani4ka.concurrency.review.storage.ReviewStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.wani4ka.concurrency.review.ReviewService.DateOrder.ASC;
import static me.wani4ka.concurrency.review.ReviewService.DateOrder.DESC;
import static me.wani4ka.concurrency.review.ReviewService.Status.RUNNING;
import static me.wani4ka.concurrency.review.ReviewService.Status.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReviewServiceTests {

    @Test
    void waitsForTheSaveAndItsSuccessStatusBeforeReading() throws Exception {
        int pluginId = 164;
        var saved = new CountDownLatch(1);
        var releaseSave = new CountDownLatch(1);
        var store = new TestReviewStore() {
            @Override
            public void saveAll(int id, List<Review> reviews) {
                super.saveAll(id, reviews);
                saved.countDown();
                try {
                    assertThat(releaseSave.await(15, SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
        };
        var client = new MarketplaceClient(WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("""
                        [{"id":42,"comment":"<p>Updated</p>","rating":4,"author":{"name":"Demo"},"cdate":"0"}]
                        """).build())));
        var service = new ReviewService(client, new MarketplaceReviewParser(), store);
        service.updateStatus(pluginId, RUNNING, null);

        var collection = new FutureTask<>(() -> service.collect(pluginId), null);
        var read = new FutureTask<>(() -> service.answer(pluginId, 1, DESC));
        var otherRead = new FutureTask<>(() -> service.answer(pluginId + 1, 1, DESC));
        var reader = new Thread(read);
        new Thread(collection).start();
        try {
            assertThat(saved.await(5, SECONDS)).isTrue();
            reader.start();
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> reader.getState() == Thread.State.BLOCKED || read.isDone());
            assertThat(read.isDone()).isFalse();
            new Thread(otherRead).start();
            assertThat(otherRead.get(5, SECONDS).reviews()).isEmpty();
        } finally {
            releaseSave.countDown();
        }

        collection.get(5, SECONDS);
        var answer = read.get(5, SECONDS);
        assertThat(answer.status()).isEqualTo(SUCCEEDED);
        assertThat(answer.completedAt()).isNotNull();
        assertThat(answer.reviews()).containsExactly(new Review(42, "Updated", 4, "Demo", Instant.EPOCH));
    }

    @Test
    void filtersByMinimumRatingAndBreaksDateTiesByIdInBothOrders() {
        var oldest = new Review(42, "Oldest", 4, "Demo", Instant.EPOCH);
        var lowerId = new Review(43, "First", 5, "Demo", Instant.ofEpochSecond(2));
        var higherId = new Review(44, "Second", 4, "Demo", Instant.ofEpochSecond(2));
        var lowRating = new Review(45, "Low", 3, "Demo", Instant.ofEpochSecond(1));
        var store = new TestReviewStore();
        store.saveAll(164, List.of(higherId, lowRating, oldest, lowerId));
        var service = new ReviewService(new MarketplaceClient(WebClient.builder()), new MarketplaceReviewParser(), store);

        assertThat(service.answer(164, 4, ASC).reviews()).containsExactly(oldest, lowerId, higherId);
        assertThat(service.answer(164, 4, DESC).reviews()).containsExactly(lowerId, higherId, oldest);
        assertThat(service.answer(164, 1, DESC).reviews()).containsExactly(lowerId, higherId, lowRating, oldest);
    }

    private static class TestReviewStore implements ReviewStore {
        private final ConcurrentHashMap<Integer, List<Review>> reviews = new ConcurrentHashMap<>();

        @Override
        public List<Review> findByPlugin(int pluginId) {
            return reviews.getOrDefault(pluginId, List.of());
        }

        @Override
        public void saveAll(int pluginId, List<Review> reviews) {
            this.reviews.put(pluginId, List.copyOf(reviews));
        }
    }
}
