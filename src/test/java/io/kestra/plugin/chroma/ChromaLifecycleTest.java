package io.kestra.plugin.chroma;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ChromaLifecycleTest extends ChromaTest {

    @Test
    void createUpsertQueryDelete() throws Exception {
        RunContext createCtx = runContextFactory.of();
        CreateCollection.Output created = CreateCollection.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .distance(Property.ofValue(Distance.COSINE))
            .build()
            .run(createCtx);

        assertThat(created.getCollectionName(), is(collectionName));
        assertThat(created.getCollectionId(), not(emptyOrNullString()));

        RunContext upsertCtx = runContextFactory.of();
        Upsert.Output upserted = Upsert.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .from(List.of(
                Map.of("id", "doc-1", "embedding", List.of(0.1, 0.2, 0.3, 0.4),
                    "document", "Berlin is the capital of Germany.", "metadata", Map.of("source", "handbook")),
                Map.of("id", "doc-2", "embedding", List.of(0.2, 0.1, 0.4, 0.3),
                    "document", "Paris is the capital of France.", "metadata", Map.of("source", "handbook")),
                Map.of("id", "doc-3", "embedding", List.of(0.9, 0.8, 0.7, 0.6),
                    "document", "Unrelated note.", "metadata", Map.of("source", "scratch"))
            ))
            .build()
            .run(upsertCtx);

        assertThat(upserted.getUpsertedCount(), is(3L));

        RunContext queryCtx = runContextFactory.of();
        FetchOutput queried = Query.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .vector(Property.ofValue(List.of(0.1, 0.2, 0.3, 0.4)))
            .maxResults(Property.ofValue(10))
            .where(Property.ofValue(Map.of("source", "handbook")))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build()
            .run(queryCtx);

        assertThat(queried.getSize(), is(2L));
        @SuppressWarnings("unchecked")
        Map<String, Object> top = (Map<String, Object>) queried.getRows().getFirst();
        assertThat(top.get("id"), is("doc-1"));
        assertThat(top, hasKey("distance"));
        assertThat(top, hasKey("document"));
        assertThat(top, hasKey("metadata"));

        RunContext deleteCtx = runContextFactory.of();
        Delete.Output deleted = Delete.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .ids(Property.ofValue(List.of("doc-1", "doc-2")))
            .build()
            .run(deleteCtx);

        assertThat(deleted.getRequestedIdCount(), is(2L));

        RunContext afterCtx = runContextFactory.of();
        FetchOutput after = Query.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .collectionName(Property.ofValue(collectionName))
            .vector(Property.ofValue(List.of(0.9, 0.8, 0.7, 0.6)))
            .maxResults(Property.ofValue(10))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build()
            .run(afterCtx);

        assertThat(after.getSize(), is(1L));
    }
}
