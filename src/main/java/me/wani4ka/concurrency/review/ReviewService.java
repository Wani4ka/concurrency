package me.wani4ka.concurrency.review;

import lombok.RequiredArgsConstructor;
import me.wani4ka.concurrency.review.marketplace.MarketplaceClient;
import me.wani4ka.concurrency.review.marketplace.MarketplaceReviewParser;
import me.wani4ka.concurrency.review.storage.ReviewStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final MarketplaceClient client;
    private final MarketplaceReviewParser parser;
    private final ReviewStore store;
    private final ConcurrentMap<Integer, JobState> states = new ConcurrentHashMap<>();
    private final Object[] storageLocks = IntStream.range(0, 16).mapToObj(index -> new Object()).toArray(Object[]::new);

    public void collect(int pluginId) {
        List<Review> reviews = parser.parse(client.fetch(pluginId));
        // Keep the committed reviews and the success status consistent with answer().
        synchronized (storageLock(pluginId)) {
            store.saveAll(pluginId, reviews);
            updateStatus(pluginId, Status.SUCCEEDED, null);
        }
    }

    public void updateStatus(int pluginId, Status status, String error) {
        states.compute(pluginId, (id, previous) -> {
            Instant now = Instant.now();
            Instant requestedAt = status == Status.QUEUED || previous == null ? now : previous.requestedAt();
            Instant completedAt = status == Status.SUCCEEDED || status == Status.FAILED ? now : null;
            return new JobState(status, requestedAt, completedAt, error);
        });
    }

    public Status status(int pluginId) {
        return state(pluginId).status();
    }

    public Answer answer(int pluginId, int minRating, DateOrder dateOrder) {
        JobState state;
        List<Review> savedReviews;
        synchronized (storageLock(pluginId)) {
            state = state(pluginId);
            savedReviews = store.findByPlugin(pluginId);
        }
        var byDate = Comparator.comparing(Review::publishedAt);
        if (dateOrder == DateOrder.DESC) {
            byDate = byDate.reversed();
        }
        var reviews = savedReviews.parallelStream()
                .filter(review -> review.rating() >= minRating)
                .sorted(byDate.thenComparingLong(Review::id))
                .toList();
        return new Answer(pluginId, state.status(), state.requestedAt(), state.completedAt(),
                reviews, state.error());
    }

    private Object storageLock(int pluginId) {
        return storageLocks[Math.floorMod(pluginId, storageLocks.length)];
    }

    private JobState state(int pluginId) {
        return states.getOrDefault(pluginId, JobState.IDLE);
    }

    private record JobState(Status status, Instant requestedAt, Instant completedAt, String error) {
        private static final JobState IDLE = new JobState(Status.IDLE, null, null, null);
    }

    public enum Status {
        IDLE, QUEUED, RUNNING, SUCCEEDED, FAILED
    }

    public enum DateOrder {
        ASC, DESC
    }

    public record Answer(
            int pluginId, Status status, Instant requestedAt, Instant completedAt, List<Review> reviews, String error) {
    }
}
