package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;

import java.util.List;

public interface ReviewStore {

    List<Review> findByPlugin(int pluginId);

    void saveAll(int pluginId, List<Review> reviews);
}
