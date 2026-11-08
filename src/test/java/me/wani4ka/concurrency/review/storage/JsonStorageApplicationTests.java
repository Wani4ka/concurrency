package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;
import me.wani4ka.concurrency.review.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static me.wani4ka.concurrency.review.ReviewService.DateOrder.ASC;
import static me.wani4ka.concurrency.review.ReviewService.Status.IDLE;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"test", "json"})
class JsonStorageApplicationTests {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void reviewDirectory(DynamicPropertyRegistry registry) {
        registry.add("reviews.directory", () -> directory.toString());
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ReviewService service;

    @Test
    void readsAndFiltersExistingJsonWithoutStartingADataSource() throws IOException {
        Files.writeString(directory.resolve("164.json"), """
                [
                  {"id":44,"text":"Newest","rating":5,"author":"Demo","publishedAt":"2026-10-29T18:00:10Z"},
                  {"id":43,"text":"Low","rating":3,"author":"Demo","publishedAt":"2026-10-29T18:00:08Z"},
                  {"id":42,"text":"Oldest","rating":4,"author":"Demo","publishedAt":"2026-10-29T18:00:09Z"}
                ]
                """);

        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        var answer = service.answer(164, 4, ASC);
        assertThat(answer.status()).isEqualTo(IDLE);
        assertThat(answer.reviews()).containsExactly(
                new Review(42, "Oldest", 4, "Demo", Instant.parse("2026-10-29T18:00:09Z")),
                new Review(44, "Newest", 5, "Demo", Instant.parse("2026-10-29T18:00:10Z")));
    }
}
