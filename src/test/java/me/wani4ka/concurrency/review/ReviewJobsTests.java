package me.wani4ka.concurrency.review;

import me.wani4ka.concurrency.web.ReviewController;
import me.wani4ka.concurrency.review.ReviewService.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.wani4ka.concurrency.review.ReviewService.Status.IDLE;
import static me.wani4ka.concurrency.review.ReviewService.Status.QUEUED;
import static me.wani4ka.concurrency.review.ReviewService.Status.RUNNING;
import static me.wani4ka.concurrency.review.ReviewService.Status.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewJobsTests {

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"SUCCEEDED", "FAILED"})
    void processesJobsInOrderAndCoalescesDuplicates(Status firstStatus) throws InterruptedException {
        var started = new CountDownLatch(1);
        var firstResponse = Sinks.<ClientResponse>one();
        var requests = new AtomicInteger();
        var service = service(request -> {
            if (requests.incrementAndGet() == 1) {
                started.countDown();
                return firstResponse.asMono();
            }
            return Mono.just(emptyResponse());
        });
        var jobs = new ReviewJobs(service);
        var worker = new ReviewWorker(jobs, service);

        assertThat(jobs.submit(1)).isTrue();
        var requestedAt = service.answer(1).requestedAt();
        assertThat(requestedAt).isNotNull();
        assertThat(jobs.submit(1)).isTrue();
        assertThat(service.answer(1).status()).isEqualTo(QUEUED);
        assertThat(service.answer(1).requestedAt()).isEqualTo(requestedAt);
        assertThat(service.answer(1).completedAt()).isNull();

        worker.start();
        try {
            assertThat(started.await(5, SECONDS)).isTrue();
            assertThat(service.answer(1).status()).isEqualTo(RUNNING);
            assertThat(jobs.submit(1)).isTrue();
            assertThat(service.answer(1).requestedAt()).isEqualTo(requestedAt);
            assertThat(service.answer(1).completedAt()).isNull();
            assertThat(jobs.submit(2)).isTrue();
            assertThat(service.answer(2).status()).isEqualTo(QUEUED);
            assertThat(requests.get()).isEqualTo(1);

            var response = firstStatus == SUCCEEDED
                    ? emptyResponse()
                    : ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build();
            firstResponse.emitValue(response, Sinks.EmitFailureHandler.FAIL_FAST);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var answer = service.answer(1);
                assertThat(answer.status()).isEqualTo(firstStatus);
                assertThat(answer.requestedAt()).isEqualTo(requestedAt);
                assertThat(answer.completedAt()).isAfterOrEqualTo(requestedAt);
                assertThat(service.answer(2).status()).isEqualTo(SUCCEEDED);
                assertThat(requests.get()).isEqualTo(2);
            });
        } finally {
            worker.stop();
        }
    }

    @Test
    void rejectsNewPluginWhenQueueIsFullWithoutChangingItsStatus() throws Exception {
        var service = service(request -> Mono.error(new AssertionError("No worker is running")));
        var jobs = new ReviewJobs(service);
        for (int id = 1; id <= 16; id++) {
            assertThat(jobs.submit(id)).isTrue();
        }

        assertThat(jobs.submit(1)).isTrue();
        MockMvcBuilders.standaloneSetup(new ReviewController(service, jobs)).build()
                .perform(post("/post").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://plugins.jetbrains.com/plugin/17-example\"}"))
                .andExpect(status().isServiceUnavailable());
        var rejected = service.answer(17);
        assertThat(rejected.status()).isEqualTo(IDLE);
        assertThat(rejected.requestedAt()).isNull();
        assertThat(rejected.completedAt()).isNull();
    }

    @Test
    void interruptsInFlightRequestAndStopsTheWorker() throws InterruptedException {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var collectionThread = new AtomicReference<Thread>();
        var service = service(request -> {
            collectionThread.set(Thread.currentThread());
            started.countDown();
            return Mono.<ClientResponse>never().doOnCancel(cancelled::countDown);
        });
        var jobs = new ReviewJobs(service);
        var worker = new ReviewWorker(jobs, service);
        assertThat(jobs.submit(1)).isTrue();

        worker.start();
        try {
            assertThat(started.await(5, SECONDS)).isTrue();
        } finally {
            worker.stop();
        }
        assertThat(cancelled.await(1, SECONDS)).isTrue();
        assertThat(collectionThread.get().getName()).isEqualTo("review-worker");
        assertThat(collectionThread.get().isAlive()).isFalse();
    }

    private static ReviewService service(ExchangeFunction exchange) {
        var client = new MarketplaceClient(WebClient.builder().exchangeFunction(exchange), Duration.ofSeconds(10));
        return new ReviewService(client, new MarketplaceReviewParser());
    }

    private static ClientResponse emptyResponse() {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("[]").build();
    }
}
