package me.wani4ka.concurrency.review.marketplace;

import me.wani4ka.concurrency.review.Review;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class MarketplaceReviewParser {

    public List<Review> parse(List<MarketplaceReviewDto> source) {
        var reviews = new ArrayList<Review>();
        for (MarketplaceReviewDto item : source) {
            if (item == null || item.id() == null || item.id() <= 0
                    || item.rating() == null || item.rating() < 0 || item.rating() > 5
                    || item.author() == null || item.author().name() == null || item.author().name().isBlank()) {
                throw invalidReview(null);
            }
            Instant publishedAt;
            try {
                publishedAt = Instant.ofEpochMilli(Long.parseLong(item.cdate()));
            } catch (NumberFormatException error) {
                throw invalidReview(error);
            }
            if (item.rating() == 0 || item.comment() == null) {
                continue;
            }
            String text = Jsoup.parseBodyFragment(item.comment()).text();
            if (!text.isBlank()) {
                reviews.add(new Review(item.id(), text, item.rating(), item.author().name(), publishedAt));
            }
        }
        return reviews;
    }

    private static MarketplaceException invalidReview(Throwable cause) {
        return new MarketplaceException("JetBrains Marketplace returned an invalid review", cause);
    }
}
