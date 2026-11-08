package me.wani4ka.concurrency.review;

import java.util.List;

interface ReviewStore {

    List<Review> findByPlugin(int pluginId);

    void saveAll(int pluginId, List<Review> reviews);
}
