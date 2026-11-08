package me.wani4ka.concurrency.review;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
        List<Review> incoming;
        try {
            incoming = parser.parse(client.fetch(pluginId));
        } catch (MarketplaceException error) {
            answers.compute(pluginId, (id, previous) ->
                    new Answer(id, "FAILED", savedReviews(previous), error.getMessage()));
            throw error;
        }
        answers.compute(pluginId, (id, previous) -> {
            var combined = new LinkedHashMap<Long, Review>();
            savedReviews(previous).forEach(review -> combined.put(review.id(), review));
            incoming.forEach(review -> combined.put(review.id(), review));
            return new Answer(id, "SUCCEEDED", List.copyOf(combined.values()), null);
        });
    }

    public Answer answer(int pluginId) {
        return answers.getOrDefault(pluginId, new Answer(pluginId, "IDLE", List.of(), null));
    }

    private static List<Review> savedReviews(Answer previous) {
        return previous == null ? List.of() : previous.reviews();
    }

    public record Answer(int pluginId, String status, List<Review> reviews, String error) {
    }
}
