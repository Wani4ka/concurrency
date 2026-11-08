package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
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
public class FileReviewStore implements ReviewStore {

    private static final TypeReference<List<Review>> REVIEW_LIST = new TypeReference<>() {};

    private final JsonMapper mapper;
    private final Path directory;

    public FileReviewStore(JsonMapper mapper, @Value("${reviews.directory}") Path directory) {
        this.mapper = mapper;
        this.directory = directory;
    }

    @Override
    public List<Review> findByPlugin(int pluginId) {
        try {
            return mapper.readValue(Files.readAllBytes(file(pluginId)), REVIEW_LIST);
        } catch (NoSuchFileException ignored) {
            return List.of();
        } catch (IOException error) {
            throw new UncheckedIOException("Could not read reviews for plugin " + pluginId, error);
        }
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
