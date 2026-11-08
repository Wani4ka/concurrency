package me.wani4ka.concurrency.review;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final MarketplaceClient client;
    private final MarketplaceReviewParser parser;
    private final ReviewStore store;
    private final ConcurrentMap<Integer, JobState> states = new ConcurrentHashMap<>();

    public void collect(int pluginId) {
        List<Review> reviews = parser.parse(client.fetch(pluginId));
        synchronized (this) {
            store.saveAll(pluginId, reviews);
            updateStatus(pluginId, Status.SUCCEEDED, null);
        }
    }

    void updateStatus(int pluginId, Status status, String error) {
        states.compute(pluginId, (id, previous) -> {
            Instant now = Instant.now();
            Instant requestedAt = status == Status.QUEUED || previous == null ? now : previous.requestedAt();
            Instant completedAt = status == Status.SUCCEEDED || status == Status.FAILED ? now : null;
            return new JobState(status, requestedAt, completedAt, error);
        });
    }

    Status status(int pluginId) {
        return state(pluginId).status();
    }

    public synchronized Answer answer(int pluginId) {
        JobState state = state(pluginId);
        return new Answer(pluginId, state.status(), state.requestedAt(), state.completedAt(),
                store.findByPlugin(pluginId), state.error());
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

    public record Answer(
            int pluginId, Status status, Instant requestedAt, Instant completedAt, List<Review> reviews, String error) {
    }
}
