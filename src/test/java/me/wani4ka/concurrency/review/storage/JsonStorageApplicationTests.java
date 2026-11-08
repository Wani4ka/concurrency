package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;
import me.wani4ka.concurrency.review.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static me.wani4ka.concurrency.review.ReviewService.DateOrder.ASC;
import static me.wani4ka.concurrency.review.ReviewService.Status.IDLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
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

    @Autowired
    private MockMvc mvc;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "[null]",
            "{",
            "[{\"id\":42,\"text\":\"Useful\",\"rating\":4,\"author\":\"Demo\"}]"
    })
    void explainsWhyCorruptedJsonCannotBeRead(String snapshot) throws Exception {
        var file = directory.resolve("165.json");
        Files.writeString(file, snapshot);

        mvc.perform(get("/answer").param("pluginId", "165"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Stored reviews are invalid for plugin 165"));
        assertThat(Files.readString(file)).isEqualTo(snapshot);
    }
}
