package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;

import java.util.List;

final class ReviewValidation {

    private ReviewValidation() {
    }

    static List<Review> requireValid(List<Review> reviews, String error) {
        if (reviews == null || reviews.stream().anyMatch(ReviewValidation::invalid)) {
            throw new ReviewStorageException(error, null);
        }
        return reviews;
    }

    private static boolean invalid(Review review) {
        return review == null || review.id() <= 0
                || review.rating() < 1 || review.rating() > 5
                || review.text() == null || review.text().isBlank()
                || review.author() == null || review.author().isBlank()
                || review.publishedAt() == null;
    }
}
