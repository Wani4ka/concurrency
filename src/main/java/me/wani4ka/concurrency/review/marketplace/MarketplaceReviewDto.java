package me.wani4ka.concurrency.review.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketplaceReviewDto(
        Long id, @JsonProperty(required = true) String comment, Integer rating, Author author, String cdate) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Author(String name) {
    }
}
