package me.wani4ka.concurrency.review;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketplaceReviewParserTests {

    private final MarketplaceReviewParser parser = new MarketplaceReviewParser();

    @Test
    void extractsTextRatingAuthorAndPublicationDate() throws IOException {
        String comment;
        try (var fixture = getClass().getResourceAsStream("/marketplace/comment.html")) {
            comment = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        }
        var source = new MarketplaceReviewDto(42L, comment, 4,
                new MarketplaceReviewDto.Author("Demo User"), "1793296810000");

        assertThat(parser.parse(List.of(source))).containsExactly(new Review(
                42, "Works well & fast.", 4, "Demo User", Instant.parse("2026-10-29T18:00:10Z")));
    }

    @Test
    void skipsRatingWithoutText() {
        var source = new MarketplaceReviewDto(42L, "<p>&nbsp;</p>", 4,
                new MarketplaceReviewDto.Author("Demo User"), "1793296810000");

        assertThat(parser.parse(List.of(source))).isEmpty();
    }

    @Test
    void skipsCommentWithoutStarRating() {
        var source = new MarketplaceReviewDto(42L, "<p>Useful</p>", 0,
                new MarketplaceReviewDto.Author("Demo User"), "1793296810000");

        assertThat(parser.parse(List.of(source))).isEmpty();
    }

    @Test
    void rejectsInvalidDateInsteadOfSavingTheReview() {
        var source = new MarketplaceReviewDto(42L, "<p>Useful</p>", 4,
                new MarketplaceReviewDto.Author("Demo User"), "unknown");

        assertThatThrownBy(() -> parser.parse(List.of(source))).isInstanceOf(MarketplaceException.class);
    }

    @Test
    void rejectsIncompleteReviewInsteadOfInventingAnAuthor() {
        var source = new MarketplaceReviewDto(42L, "<p>Useful</p>", 4, null, "1793296810000");

        assertThatThrownBy(() -> parser.parse(List.of(source))).isInstanceOf(MarketplaceException.class);
    }
}
