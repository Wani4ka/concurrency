package me.wani4ka.concurrency.review;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import static java.util.concurrent.TimeUnit.SECONDS;
import static me.wani4ka.concurrency.review.ReviewService.Status.FAILED;
import static me.wani4ka.concurrency.review.ReviewService.Status.RUNNING;

@Component
@Slf4j
class ReviewScheduler {

    private static final int WORKERS = 2;

    private final ReviewJobs jobs;
    private final ReviewService service;
    private final ReviewLogWorker journal;
    private final ExecutorService pool;
    private final Semaphore slots = new Semaphore(WORKERS);

    @Autowired
    ReviewScheduler(ReviewJobs jobs, ReviewService service, ReviewLogWorker journal) {
        this(jobs, service, journal,
                Executors.newFixedThreadPool(WORKERS, new CustomizableThreadFactory("review-worker-")));
    }

    ReviewScheduler(ReviewJobs jobs, ReviewService service, ReviewLogWorker journal, ExecutorService pool) {
        this.jobs = jobs;
        this.service = service;
        this.journal = journal;
        this.pool = pool;
    }

    @Scheduled(fixedDelay = 1_000)
    void dispatch() {
        while (slots.tryAcquire()) {
            Integer pluginId = jobs.poll();
            if (pluginId == null) {
                slots.release();
                return;
            }
            try {
                pool.execute(() -> collect(pluginId));
            } catch (RejectedExecutionException error) {
                slots.release();
                fail(pluginId, "Review collection pool is unavailable", error);
            }
        }
    }

    private void collect(int pluginId) {
        try {
            service.updateStatus(pluginId, RUNNING, null);
            service.collect(pluginId);
            journal.record(pluginId, null, null);
        } catch (MarketplaceException error) {
            fail(pluginId, error.getMessage(), null);
        } catch (RuntimeException error) {
            fail(pluginId, "Review collection failed", error);
        } finally {
            slots.release();
        }
    }

    private void fail(int pluginId, String message, Throwable cause) {
        service.updateStatus(pluginId, FAILED, message);
        journal.record(pluginId, message, cause);
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(2, SECONDS)) {
                log.warn("Review collection pool did not stop within two seconds");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
