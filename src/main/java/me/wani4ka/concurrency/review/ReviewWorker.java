package me.wani4ka.concurrency.review;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static me.wani4ka.concurrency.review.ReviewService.Status.FAILED;
import static me.wani4ka.concurrency.review.ReviewService.Status.RUNNING;

@Component
@RequiredArgsConstructor
@Slf4j
class ReviewWorker {

    private final ReviewJobs jobs;
    private final ReviewService service;
    private Thread thread;

    @PostConstruct
    void start() {
        Runnable task = this::run;
        thread = new Thread(task, "review-worker");
        thread.start();
    }

    @PreDestroy
    void stop() {
        thread.interrupt();
        try {
            thread.join(2_000);
            if (thread.isAlive()) {
                log.warn("Review worker did not stop within two seconds");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            int pluginId;
            try {
                pluginId = jobs.take();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            service.updateStatus(pluginId, RUNNING, null);
            try {
                service.collect(pluginId);
            } catch (MarketplaceException error) {
                service.updateStatus(pluginId, FAILED, error.getMessage());
            } catch (RuntimeException error) {
                if (Thread.currentThread().isInterrupted()) {
                    service.updateStatus(pluginId, FAILED, "Review collection interrupted");
                    return;
                }
                log.error("Review collection failed for plugin {}", pluginId, error);
                service.updateStatus(pluginId, FAILED, "Review collection failed");
            }
        }
    }
}
