package io.kestra.plugin.chroma;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.YamlParser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

// Parses flow YAML the way the editor does, so property-name binding bugs (a Lombok getter Jackson cannot map back) are caught.
@KestraTest
class ChromaYamlTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void queryMaxResultsIsSettableFromYaml() throws Exception {
        var flow = YamlParser.parse("""
            id: chroma_query
            namespace: qa.chroma

            tasks:
              - id: search
                type: io.kestra.plugin.chroma.Query
                baseUrl: http://localhost:8000
                collectionName: docs
                vector: [0.05, 0.61, 0.76, 0.74]
                maxResults: 5
                where:
                  source: handbook
                fetchType: FETCH
            """, Flow.class);

        var query = (Query) flow.getTasks().getFirst();
        var maxResults = runContextFactory.of().render(query.getMaxResults()).as(Integer.class).orElseThrow();
        assertThat(maxResults, is(5));
    }

    @Test
    void allTasksDeserializeFromYaml() {
        var flow = YamlParser.parse("""
            id: chroma_all
            namespace: qa.chroma

            tasks:
              - id: create
                type: io.kestra.plugin.chroma.CreateCollection
                baseUrl: http://localhost:8000
                collectionName: docs
                distance: COSINE
              - id: upsert
                type: io.kestra.plugin.chroma.Upsert
                baseUrl: http://localhost:8000
                collectionName: docs
                batchSize: 200
                from: "{{ outputs.embed.uri }}"
              - id: search
                type: io.kestra.plugin.chroma.Query
                baseUrl: http://localhost:8000
                collectionName: docs
                vector: [0.1, 0.2, 0.3, 0.4]
                maxResults: 3
                fetchType: FETCH
              - id: delete
                type: io.kestra.plugin.chroma.Delete
                baseUrl: http://localhost:8000
                collectionName: docs
                ids: ["doc-1"]
            """, Flow.class);

        assertThat(flow.getTasks().get(0), instanceOf(CreateCollection.class));
        assertThat(flow.getTasks().get(1), instanceOf(Upsert.class));
        assertThat(flow.getTasks().get(2), instanceOf(Query.class));
        assertThat(flow.getTasks().get(3), instanceOf(Delete.class));
    }
}
