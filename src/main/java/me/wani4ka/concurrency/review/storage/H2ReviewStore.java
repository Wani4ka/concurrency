package me.wani4ka.concurrency.review.storage;

import lombok.RequiredArgsConstructor;
import me.wani4ka.concurrency.review.Review;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@Profile("!json")
@RequiredArgsConstructor
class H2ReviewStore implements ReviewStore {

    private final ReviewRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<Review> findByPlugin(int pluginId) {
        var reviews = repository.findAllByPluginId(pluginId).stream().map(ReviewEntity::toReview).toList();
        return ReviewValidation.requireValid(reviews, "Stored reviews are invalid for plugin " + pluginId);
    }

    @Override
    @Transactional
    public void saveAll(int pluginId, List<Review> reviews) {
        ReviewValidation.requireValid(reviews, "Invalid review batch for plugin " + pluginId);
        repository.saveAll(reviews.stream().map(review -> new ReviewEntity(pluginId, review)).toList());
    }
}
