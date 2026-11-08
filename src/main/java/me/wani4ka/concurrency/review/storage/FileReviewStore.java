package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Repository
@Profile("json")
class FileReviewStore implements ReviewStore {

    private static final TypeReference<List<Review>> REVIEW_LIST = new TypeReference<>() {};

    private final JsonMapper mapper;
    private final Path directory;

    FileReviewStore(JsonMapper mapper, @Value("${reviews.directory}") Path directory) {
        this.mapper = mapper;
        this.directory = directory;
    }

    @Override
    public List<Review> findByPlugin(int pluginId) {
        try {
            List<Review> reviews = mapper.readValue(Files.readAllBytes(file(pluginId)), REVIEW_LIST);
            if (reviews == null || reviews.stream().anyMatch(FileReviewStore::invalidReview)) {
                throw invalidSnapshot(pluginId, null);
            }
            return reviews;
        } catch (NoSuchFileException ignored) {
            return List.of();
        } catch (JacksonException error) {
            throw invalidSnapshot(pluginId, error);
        } catch (IOException error) {
            throw new ReviewStorageException("Could not read stored reviews for plugin " + pluginId, error);
        }
    }

    private static boolean invalidReview(Review review) {
        return review == null || review.id() <= 0
                || review.rating() < 1 || review.rating() > 5
                || review.text() == null || review.text().isBlank()
                || review.author() == null || review.author().isBlank()
                || review.publishedAt() == null;
    }

    private static ReviewStorageException invalidSnapshot(int pluginId, Throwable cause) {
        return new ReviewStorageException("Stored reviews are invalid for plugin " + pluginId, cause);
    }

    @Override
    public void saveAll(int pluginId, List<Review> reviews) {
        var combined = new LinkedHashMap<Long, Review>();
        findByPlugin(pluginId).forEach(review -> combined.put(review.id(), review));
        reviews.forEach(review -> combined.put(review.id(), review));

        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "reviews-", ".tmp");
            try {
                try (var output = Files.newOutputStream(temporary)) {
                    mapper.writeValue(output, combined.values());
                }
                Files.move(temporary, file(pluginId), ATOMIC_MOVE, REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Could not save reviews for plugin " + pluginId, error);
        }
    }

    private Path file(int pluginId) {
        return directory.resolve(pluginId + ".json");
    }
}
