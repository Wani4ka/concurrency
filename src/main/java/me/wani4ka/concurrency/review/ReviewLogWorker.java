package me.wani4ka.concurrency.review;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@Component
@Slf4j
class ReviewLogWorker {

    private final BlockingQueue<Event> events = new ArrayBlockingQueue<>(64);
    private Thread thread;

    @PostConstruct
    void start() {
        thread = new Thread(this::run, "review-log");
        thread.setDaemon(true);
        thread.start();
    }

    void record(int pluginId, String error, Throwable cause) {
        // shortcut: drop new log events when the queue is full, revisit if logs become an audit trail.
        events.offer(new Event(pluginId, error, cause));
    }

    private void run() {
        try {
            while (true) {
                log(events.take());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            Event event;
            while ((event = events.poll()) != null) {
                log(event);
            }
        }
    }

    private void log(Event event) {
        if (event.error() == null) {
            log.info("Review collection succeeded for plugin {}", event.pluginId());
        } else {
            log.warn("Review collection failed for plugin {}: {}", event.pluginId(), event.error(), event.cause());
        }
    }

    @PreDestroy
    void stop() {
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private record Event(int pluginId, String error, Throwable cause) {
    }
}
