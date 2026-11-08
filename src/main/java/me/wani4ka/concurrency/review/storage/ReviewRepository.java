package me.wani4ka.concurrency.review.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ReviewRepository extends JpaRepository<ReviewEntity, ReviewEntity.Key> {

    List<ReviewEntity> findAllByPluginId(int pluginId);
}
