package io.kestra.plugin.chroma;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Input-validation cases that fail before any network call, so they run without Docker.
@KestraTest
class ChromaValidationTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void deleteWithoutAnyCriteriaFails() {
        var task = Delete.builder()
            .baseUrl(Property.ofValue("http://localhost:8000"))
            .collectionName(Property.ofValue("docs"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(exception.getMessage(), containsString("At least one of 'ids'"));
    }

    @Test
    void queryWithEmptyVectorFails() {
        var task = Query.builder()
            .baseUrl(Property.ofValue("http://localhost:8000"))
            .collectionName(Property.ofValue("docs"))
            .vector(Property.ofValue(List.of()))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(exception.getMessage(), containsString("non-empty list"));
    }

    @Test
    void basicAuthWithOnlyUsernameFails() {
        var task = CreateCollection.builder()
            .baseUrl(Property.ofValue("http://localhost:8000"))
            .collectionName(Property.ofValue("docs"))
            .username(Property.ofValue("user"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(exception.getMessage(), containsString("both 'username' and 'password'"));
    }

    @Test
    void queryWithOutOfRangeMaxResultsFails() {
        var task = Query.builder()
            .baseUrl(Property.ofValue("http://localhost:8000"))
            .collectionName(Property.ofValue("docs"))
            .vector(Property.ofValue(List.of(0.1, 0.2, 0.3, 0.4)))
            .maxResults(Property.ofValue(0))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(exception.getMessage(), containsString("'maxResults' must be between 1 and 10000"));
    }

    @Test
    void upsertWithOutOfRangeBatchSizeFails() {
        var task = Upsert.builder()
            .baseUrl(Property.ofValue("http://localhost:8000"))
            .collectionName(Property.ofValue("docs"))
            .from(List.of(Map.of("id", "doc-1", "embedding", List.of(0.1, 0.2, 0.3, 0.4))))
            .batchSize(Property.ofValue(0))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(exception.getMessage(), containsString("'batchSize' must be between 1 and 10000"));
    }

    @Test
    void tokenAndBasicAuthTogetherFails() {
        var task = CreateCollection.builder()
            .baseUrl(Property.ofValue("http://localhost:8000"))
            .collectionName(Property.ofValue("docs"))
            .authToken(Property.ofValue("a-token"))
            .username(Property.ofValue("user"))
            .password(Property.ofValue("pass"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(exception.getMessage(), containsString("either 'authToken' or 'username'/'password'"));
    }
}
