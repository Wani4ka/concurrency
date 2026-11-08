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
        return repository.findAllByPluginId(pluginId).stream().map(ReviewEntity::toReview).toList();
    }

    @Override
    @Transactional
    public void saveAll(int pluginId, List<Review> reviews) {
        repository.saveAll(reviews.stream().map(review -> new ReviewEntity(pluginId, review)).toList());
    }
}
