package me.wani4ka.concurrency.review.storage;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import me.wani4ka.concurrency.review.Review;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "reviews", check = @CheckConstraint(constraint = "rating between 1 and 5"))
@IdClass(ReviewEntity.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ReviewEntity {

    @Id
    private int pluginId;

    @Id
    private long id;

    @Lob
    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private int rating;

    @Lob
    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private Instant publishedAt;

    ReviewEntity(int pluginId, Review review) {
        this.pluginId = pluginId;
        id = review.id();
        text = review.text();
        rating = review.rating();
        author = review.author();
        publishedAt = review.publishedAt();
    }

    Review toReview() {
        return new Review(id, text, rating, author, publishedAt);
    }

    public record Key(int pluginId, long id) implements Serializable {
    }
}
