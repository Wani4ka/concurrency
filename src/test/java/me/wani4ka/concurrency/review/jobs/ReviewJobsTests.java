package me.wani4ka.concurrency.review.jobs;

import me.wani4ka.concurrency.review.ReviewService;
import me.wani4ka.concurrency.review.ReviewService.Status;
import me.wani4ka.concurrency.review.marketplace.MarketplaceClient;
import me.wani4ka.concurrency.review.marketplace.MarketplaceReviewParser;
import me.wani4ka.concurrency.review.storage.FileReviewStore;
import me.wani4ka.concurrency.review.web.ReviewController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @TempDir
    Path reviewsDirectory;

    @Test
    void concurrentSubmissionsForTheSamePluginCreateOneJob() throws Exception {
        var publishing = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstUpdate = new AtomicBoolean(true);
        var service = new ReviewService(new MarketplaceClient(WebClient.builder()), new MarketplaceReviewParser(),
                new FileReviewStore(new JsonMapper(), reviewsDirectory)) {
            @Override
            public void updateStatus(int pluginId, Status status, String error) {
                if (firstUpdate.getAndSet(false)) {
                    publishing.countDown();
                    try {
                        assertThat(releaseFirst.await(15, SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                super.updateStatus(pluginId, status, error);
            }
        };
        var jobs = new ReviewJobs(service);
        var firstResult = new FutureTask<>(() -> jobs.submit(1));
        var secondResult = new FutureTask<>(() -> jobs.submit(1));
        var first = new Thread(firstResult);
        var second = new Thread(secondResult);
        var threads = ManagementFactory.getThreadMXBean();

        first.start();
        try {
            assertThat(publishing.await(5, SECONDS)).isTrue();
            second.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                var info = threads.getThreadInfo(second.getId());
                return secondResult.isDone() || (info != null && info.getLockOwnerId() == first.getId());
            });
        } finally {
            releaseFirst.countDown();
        }

        assertThat(firstResult.get(5, SECONDS)).isTrue();
        assertThat(secondResult.get(5, SECONDS)).isTrue();
        assertThat(jobs.poll()).isEqualTo(1);
        assertThat(jobs.poll()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"SUCCEEDED", "FAILED"})
    void dispatchesTwoJobsAndKeepsThirdQueuedUntilASlotIsFree(Status firstStatus) throws InterruptedException {
        var started = new CountDownLatch(2);
        var firstResponse = Sinks.<ClientResponse>one();
        var service = service(request -> switch (request.url().getPath()) {
            case "/api/plugins/1/comments" -> {
                started.countDown();
                yield firstResponse.asMono();
            }
            case "/api/plugins/2/comments" -> {
                started.countDown();
                yield Mono.never();
            }
            default -> Mono.just(emptyResponse());
        });
        var jobs = new ReviewJobs(service);
        var submissions = new AtomicInteger();
        var pool = new ThreadPoolExecutor(2, 2, 0, SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                submissions.incrementAndGet();
                super.execute(command);
            }
        };
        var scheduler = new ReviewScheduler(jobs, service, new ReviewLogWorker(), pool);

        assertThat(jobs.submit(1)).isTrue();
        var requestedAt = service.answer(1).requestedAt();
        assertThat(requestedAt).isNotNull();
        assertThat(jobs.submit(1)).isTrue();
        assertThat(service.answer(1).status()).isEqualTo(QUEUED);
        assertThat(service.answer(1).requestedAt()).isEqualTo(requestedAt);
        assertThat(service.answer(1).completedAt()).isNull();

        assertThat(jobs.submit(2)).isTrue();
        assertThat(jobs.submit(3)).isTrue();
        try {
            scheduler.dispatch();
            assertThat(started.await(5, SECONDS)).isTrue();
            assertThat(service.answer(1).status()).isEqualTo(RUNNING);
            assertThat(jobs.submit(1)).isTrue();
            assertThat(service.answer(1).requestedAt()).isEqualTo(requestedAt);
            assertThat(service.answer(1).completedAt()).isNull();
            assertThat(service.answer(2).status()).isEqualTo(RUNNING);
            assertThat(service.answer(3).status()).isEqualTo(QUEUED);
            scheduler.dispatch();
            assertThat(submissions.get()).isEqualTo(2);

            var response = firstStatus == SUCCEEDED
                    ? emptyResponse()
                    : ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build();
            firstResponse.emitValue(response, Sinks.EmitFailureHandler.FAIL_FAST);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var answer = service.answer(1);
                assertThat(answer.status()).isEqualTo(firstStatus);
                assertThat(answer.requestedAt()).isEqualTo(requestedAt);
                assertThat(answer.completedAt()).isAfterOrEqualTo(requestedAt);
                scheduler.dispatch();
                assertThat(service.answer(3).status()).isEqualTo(SUCCEEDED);
            });
            assertThat(submissions.get()).isEqualTo(3);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void rejectedSubmissionsFailAndReleaseTheirSlots() {
        var service = service(request -> Mono.just(emptyResponse()));
        var jobs = new ReviewJobs(service);
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var scheduler = new ReviewScheduler(jobs, service, new ReviewLogWorker(), executor);
        jobs.submit(1);
        jobs.submit(2);
        jobs.submit(3);

        scheduler.dispatch();

        assertThat(service.answer(1).status()).isEqualTo(Status.FAILED);
        assertThat(service.answer(2).status()).isEqualTo(Status.FAILED);
        assertThat(service.answer(3).status()).isEqualTo(Status.FAILED);
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
    void interruptsInFlightRequestWhenThePoolStops() throws InterruptedException {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var collectionThread = new AtomicReference<Thread>();
        var service = service(request -> {
            collectionThread.set(Thread.currentThread());
            started.countDown();
            return Mono.<ClientResponse>never().doOnCancel(cancelled::countDown);
        });
        var jobs = new ReviewJobs(service);
        var scheduler = new ReviewScheduler(jobs, service, new ReviewLogWorker());
        assertThat(jobs.submit(1)).isTrue();

        try {
            scheduler.dispatch();
            assertThat(started.await(5, SECONDS)).isTrue();
        } finally {
            scheduler.stop();
        }
        assertThat(cancelled.await(1, SECONDS)).isTrue();
        assertThat(collectionThread.get().getName()).startsWith("review-worker-");
        await().atMost(Duration.ofSeconds(1)).until(() -> !collectionThread.get().isAlive());
    }

    private ReviewService service(ExchangeFunction exchange) {
        var client = new MarketplaceClient(WebClient.builder().exchangeFunction(exchange));
        return new ReviewService(client, new MarketplaceReviewParser(),
                new FileReviewStore(new JsonMapper(), reviewsDirectory));
    }

    private static ClientResponse emptyResponse() {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("[]").build();
    }
}
