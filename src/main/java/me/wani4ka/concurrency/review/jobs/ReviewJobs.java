package me.wani4ka.concurrency.review.jobs;

import lombok.RequiredArgsConstructor;
import me.wani4ka.concurrency.review.ReviewService;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static me.wani4ka.concurrency.review.ReviewService.Status.QUEUED;
import static me.wani4ka.concurrency.review.ReviewService.Status.RUNNING;

@Component
@RequiredArgsConstructor
public class ReviewJobs {

    private final ReviewService service;
    private final BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(16);

    public synchronized boolean submit(int pluginId) {
        var status = service.status(pluginId);
        if (status == QUEUED || status == RUNNING) {
            return true;
        }
        if (queue.remainingCapacity() == 0) {
            return false;
        }
        // Publish QUEUED before the worker can take the job.
        service.updateStatus(pluginId, QUEUED, null);
        queue.add(pluginId);
        return true;
    }

    Integer poll() {
        return queue.poll();
    }
}
