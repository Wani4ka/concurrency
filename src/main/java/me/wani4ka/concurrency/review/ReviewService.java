package me.wani4ka.concurrency.review;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final MarketplaceClient client;
    private final MarketplaceReviewParser parser;
    // shortcut: snapshots live in memory until the storage iterations.
    private final ConcurrentMap<Integer, Answer> answers = new ConcurrentHashMap<>();

    public void collect(int pluginId) {
        List<Review> incoming = parser.parse(client.fetch(pluginId));
        answers.compute(pluginId, (id, previous) -> {
            var combined = new LinkedHashMap<Long, Review>();
            savedReviews(previous).forEach(review -> combined.put(review.id(), review));
            incoming.forEach(review -> combined.put(review.id(), review));
            return transition(id, previous, Status.SUCCEEDED, List.copyOf(combined.values()), null);
        });
    }

    void updateStatus(int pluginId, Status status, String error) {
        answers.compute(pluginId, (id, previous) -> transition(id, previous, status, savedReviews(previous), error));
    }

    public Answer answer(int pluginId) {
        return answers.getOrDefault(pluginId, new Answer(pluginId, Status.IDLE, null, null, List.of(), null));
    }

    private static Answer transition(int pluginId, Answer previous, Status status, List<Review> reviews, String error) {
        Instant now = Instant.now();
        Instant requestedAt = status == Status.QUEUED || previous == null ? now : previous.requestedAt();
        Instant completedAt = status == Status.SUCCEEDED || status == Status.FAILED ? now : null;
        return new Answer(pluginId, status, requestedAt, completedAt, reviews, error);
    }

    private static List<Review> savedReviews(Answer previous) {
        return previous == null ? List.of() : previous.reviews();
    }

    public enum Status {
        IDLE, QUEUED, RUNNING, SUCCEEDED, FAILED
    }

    public record Answer(
            int pluginId, Status status, Instant requestedAt, Instant completedAt, List<Review> reviews, String error) {
    }
}
