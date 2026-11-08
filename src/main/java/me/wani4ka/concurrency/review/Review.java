package me.wani4ka.concurrency.review;

import java.time.Instant;

public record Review(long id, String text, int rating, String author, Instant publishedAt) {
}
