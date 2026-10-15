package io.kestra.plugin.chroma;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.FileSerde;
import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChromaIntegrationTest extends ChromaTest {

    @Test
    void queryingAMissingCollectionFails() {
        var task = Query.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue("missing-" + collectionName))
            .vector(Property.ofValue(List.of(0.1, 0.2, 0.3, 0.4)))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(exception.getMessage(), endsWith("was not found."));
    }

    @Test
    void queryWithFetchOneReturnsASingleRow() throws Exception {
        seedCollection();

        var output = Query.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .vector(Property.ofValue(List.of(0.1, 0.2, 0.3, 0.4)))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getSize(), is(1L));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().get("id"), notNullValue());
    }

    @Test
    void queryWithStoreWritesAnIonFile() throws Exception {
        seedCollection();

        var output = Query.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .vector(Property.ofValue(List.of(0.1, 0.2, 0.3, 0.4)))
            .maxResults(Property.ofValue(10))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getUri().toString(), endsWith(".ion"));
        assertThat(output.getSize(), greaterThan(0L));
    }

    @Test
    void upsertReadsRecordsFromAStorageUri() throws Exception {
        createCollection();

        var runContext = runContextFactory.of();
        var file = runContext.workingDir().createTempFile(".ion").toFile();
        try (var output = new BufferedOutputStream(new FileOutputStream(file))) {
            FileSerde.write(output, Map.of("id", "doc-1", "embedding", List.of(0.1, 0.2, 0.3, 0.4)));
            FileSerde.write(output, Map.of("id", "doc-2", "embedding", List.of(0.2, 0.1, 0.4, 0.3)));
        }
        var uri = runContext.storage().putFile(file);

        var upserted = Upsert.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .from(uri.toString())
            .build()
            .run(runContext);

        assertThat(upserted.getUpsertedCount(), is(2L));
    }

    private void createCollection() throws Exception {
        CreateCollection.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .distance(Property.ofValue(Distance.COSINE))
            .build()
            .run(runContextFactory.of());
    }

    private void seedCollection() throws Exception {
        createCollection();
        Upsert.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .from(List.of(
                Map.of("id", "doc-1", "embedding", List.of(0.1, 0.2, 0.3, 0.4), "document", "first"),
                Map.of("id", "doc-2", "embedding", List.of(0.2, 0.1, 0.4, 0.3), "document", "second")
            ))
            .build()
            .run(runContextFactory.of());
    }
}
