package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileReviewStoreTests {

    private static final Review ORIGINAL = new Review(42, "Works", 4, "Demo", Instant.EPOCH);
    private static final Review UPDATED = new Review(42, "Works better", 5, "Demo", Instant.EPOCH);
    private static final Review OTHER = new Review(43, "Useful", 5, "User", Instant.EPOCH);

    @TempDir
    Path directory;

    @Test
    void reopensReviewsAndUpdatesByIdWithinEachPlugin() {
        var store = new FileReviewStore(new JsonMapper(), directory);
        assertThat(store.findByPlugin(164)).isEmpty();
        store.saveAll(164, List.of(ORIGINAL, OTHER));
        store.saveAll(165, List.of(ORIGINAL));

        var reopened = new FileReviewStore(new JsonMapper(), directory);
        reopened.saveAll(164, List.of(UPDATED));

        assertThat(store.findByPlugin(164)).containsExactly(UPDATED, OTHER);
        assertThat(store.findByPlugin(165)).containsExactly(ORIGINAL);
    }

    @Test
    void preservesPreviousSnapshotAndRemovesTemporaryFileWhenWritingFails() {
        var store = new FileReviewStore(new JsonMapper(), directory);
        store.saveAll(164, List.of(ORIGINAL));
        var failingMapper = new JsonMapper() {
            @Override
            public void writeValue(OutputStream output, Object value) {
                super.writeValue(output, value);
                throw new IllegalStateException("Write failed");
            }
        };

        assertThatThrownBy(() -> new FileReviewStore(failingMapper, directory).saveAll(164, List.of(UPDATED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.findByPlugin(164)).containsExactly(ORIGINAL);
        assertThat(directory).isDirectoryContaining(path -> path.getFileName().toString().equals("164.json"))
                .isDirectoryNotContaining(path -> path.getFileName().toString().endsWith(".tmp"));
    }
}
