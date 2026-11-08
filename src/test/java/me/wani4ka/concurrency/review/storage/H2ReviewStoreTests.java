package me.wani4ka.concurrency.review.storage;

import me.wani4ka.concurrency.review.Review;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(H2ReviewStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class H2ReviewStoreTests {

    private static final Review ORIGINAL = new Review(42, "Works", 4, "Demo", Instant.EPOCH);
    private static final Review UPDATED = new Review(42, "Works better", 5, "Demo", Instant.EPOCH);
    private static final Review OTHER = new Review(43, "Useful", 5, "User", Instant.EPOCH);

    @Autowired
    private ReviewStore store;

    @Test
    void savesAndUpdatesReviewsByIdWithinEachPlugin() {
        assertThat(store.findByPlugin(164)).isEmpty();
        store.saveAll(164, List.of(ORIGINAL, OTHER, UPDATED));
        store.saveAll(165, List.of(ORIGINAL));
        assertThat(store.findByPlugin(164)).containsExactlyInAnyOrder(UPDATED, OTHER);

        store.saveAll(164, List.of(ORIGINAL));

        assertThat(store.findByPlugin(164)).containsExactlyInAnyOrder(ORIGINAL, OTHER);
        assertThat(store.findByPlugin(165)).containsExactly(ORIGINAL);
    }

    @Test
    void rollsBackTheEntireBatchWhenOneReviewViolatesAConstraint() {
        int pluginId = 166;
        store.saveAll(pluginId, List.of(ORIGINAL));
        var invalid = new Review(44, "Invalid", 0, "Demo", Instant.EPOCH);

        assertThatThrownBy(() -> store.saveAll(pluginId, List.of(UPDATED, OTHER, invalid)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(store.findByPlugin(pluginId)).containsExactly(ORIGINAL);
    }
}
