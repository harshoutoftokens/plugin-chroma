package io.kestra.plugin.chroma;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Query a Chroma collection",
    description = "Runs a nearest-neighbour search with a query vector, optionally filtered by metadata (`where`) "
        + "and document text (`whereDocument`). Returns the matches as rows: inline, first-only, or written to Kestra storage."
)
@Plugin(
    examples = {
        @Example(
            title = "Search for the 5 nearest records and return them inline",
            full = true,
            code = """
                id: chroma_query
                namespace: company.team

                tasks:
                  - id: search
                    type: io.kestra.plugin.chroma.Query
                    baseUrl: http://localhost:8000
                    collectionName: docs
                    vector: [0.05, 0.61, 0.76, 0.74]
                    nResults: 5
                    where:
                      source: handbook
                    fetchType: FETCH
                """
        )
    }
)
public class Query extends ChromaConnection implements RunnableTask<FetchOutput> {

    private static final List<String> DEFAULT_INCLUDE = List.of("metadatas", "documents", "distances");

    @Schema(
        title = "Collection name",
        description = "The name of the collection to search."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> collectionName;

    @Schema(
        title = "Query vector",
        description = "The embedding to search with, as a list of numbers."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<Object>> vector;

    @Schema(
        title = "Number of results",
        description = "Maximum number of nearest records to return (default: 10, min: 1)."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> nResults = Property.ofValue(10);

    @Schema(
        title = "Metadata filter",
        description = "A Chroma `where` filter on record metadata, e.g. `{source: handbook}` or `{year: {$gte: 2020}}`."
    )
    @PluginProperty(group = "processing")
    private Property<Map<String, Object>> where;

    @Schema(
        title = "Document filter",
        description = "A Chroma `where_document` full-text filter, e.g. `{$contains: \"capital\"}`."
    )
    @PluginProperty(group = "processing")
    private Property<Map<String, Object>> whereDocument;

    @Schema(
        title = "Fields to include",
        description = "Which fields to return for each match (default: `metadatas`, `documents`, `distances`). "
            + "Valid values: `distances`, `documents`, `metadatas`, `embeddings`, `uris`."
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> include;

    @Schema(
        title = "Select fetch behavior",
        description = "FETCH returns all rows inline, FETCH_ONE returns the first row, and STORE writes the rows to "
            + "Kestra storage as an ION file and returns the URI (default: STORE)."
    )
    @Builder.Default
    @NotNull
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        String rCollectionName = runContext.render(this.collectionName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'collectionName' is required"));
        List<Object> rVector = runContext.render(this.vector).asList(Object.class);
        if (rVector.isEmpty()) {
            throw new IllegalArgumentException("'vector' must be a non-empty list of numbers.");
        }
        int rNResults = runContext.render(this.nResults).as(Integer.class).orElse(10);
        if (rNResults < 1) {
            throw new IllegalArgumentException("'nResults' must be at least 1, got: " + rNResults);
        }
        FetchType rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);
        List<String> rInclude = this.include == null
            ? DEFAULT_INCLUDE
            : runContext.render(this.include).asList(String.class);
        Map<String, Object> rWhere = this.where == null
            ? null
            : runContext.render(this.where).asMap(String.class, Object.class);
        Map<String, Object> rWhereDocument = this.whereDocument == null
            ? null
            : runContext.render(this.whereDocument).asMap(String.class, Object.class);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(rVector));
        body.put("n_results", rNResults);
        body.put("include", rInclude);
        if (rWhere != null && !rWhere.isEmpty()) {
            body.put("where", rWhere);
        }
        if (rWhereDocument != null && !rWhereDocument.isEmpty()) {
            body.put("where_document", rWhereDocument);
        }

        try (ApiClient api = api(runContext)) {
            String collectionId = api.resolveCollectionId(rCollectionName);
            runContext.logger().info("Querying collection '{}' for {} nearest records", rCollectionName, rNResults);
            JsonNode response = api.request("POST", api.recordPath(collectionId, "query"), body);

            List<Map<String, Object>> rows = toRows(response);
            return buildFetchOutput(runContext, rFetchType, rows);
        }
    }

    private static List<Map<String, Object>> toRows(JsonNode response) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (response == null) {
            return rows;
        }
        JsonNode ids = response.get("ids");
        if (ids == null || !ids.isArray()) {
            return rows;
        }

        int groups = ids.size();
        for (int g = 0; g < groups; g++) {
            JsonNode idGroup = ids.get(g);
            JsonNode distances = group(response, "distances", g);
            JsonNode documents = group(response, "documents", g);
            JsonNode metadatas = group(response, "metadatas", g);
            JsonNode embeddings = group(response, "embeddings", g);

            for (int j = 0; j < idGroup.size(); j++) {
                Map<String, Object> row = new LinkedHashMap<>();
                if (groups > 1) {
                    row.put("queryIndex", g);
                }
                row.put("id", idGroup.get(j).asText());
                putIfPresent(row, "distance", distances, j, true);
                putIfPresent(row, "document", documents, j, false);
                putIfPresent(row, "metadata", metadatas, j, false);
                putIfPresent(row, "embedding", embeddings, j, false);
                rows.add(row);
            }
        }
        return rows;
    }

    private static JsonNode group(JsonNode response, String field, int g) {
        JsonNode node = response.get(field);
        if (node == null || !node.isArray() || g >= node.size() || node.get(g).isNull()) {
            return null;
        }
        return node.get(g);
    }

    private static void putIfPresent(Map<String, Object> row, String key, JsonNode group, int j, boolean asDouble) {
        if (group == null || j >= group.size() || group.get(j).isNull()) {
            return;
        }
        JsonNode value = group.get(j);
        if (asDouble) {
            row.put(key, value.asDouble());
        } else {
            row.put(key, MAPPER.convertValue(value, Object.class));
        }
    }
}
