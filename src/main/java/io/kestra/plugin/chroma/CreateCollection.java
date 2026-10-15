package io.kestra.plugin.chroma;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a Chroma collection",
    description = "Creates a collection in the configured tenant and database. By default it is a get-or-create, "
        + "so running it against an existing collection returns that collection instead of failing. "
        + "Chroma infers the vector dimension from the first records you add."
)
@Plugin(
    examples = {
        @Example(
            title = "Create a collection with cosine distance",
            full = true,
            code = """
                id: chroma_create_collection
                namespace: company.team

                tasks:
                  - id: create_collection
                    type: io.kestra.plugin.chroma.CreateCollection
                    baseUrl: http://localhost:8000
                    collectionName: docs
                    distance: COSINE
                """
        ),
        @Example(
            title = "Create a collection on Chroma Cloud using a token stored as a secret",
            full = true,
            code = """
                id: chroma_cloud_create_collection
                namespace: company.team

                tasks:
                  - id: create_collection
                    type: io.kestra.plugin.chroma.CreateCollection
                    baseUrl: https://api.trychroma.com
                    tenant: "{{ secret('CHROMA_TENANT') }}"
                    database: "{{ secret('CHROMA_DATABASE') }}"
                    authToken: "{{ secret('CHROMA_TOKEN') }}"
                    collectionName: docs
                    distance: COSINE
                """
        )
    }
)
public class CreateCollection extends ChromaConnection implements RunnableTask<CreateCollection.Output> {

    @Schema(
        title = "Collection name",
        description = "The name of the collection to create."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> collectionName;

    @Schema(
        title = "Distance metric",
        description = "Distance function for similarity search. When unset, Chroma uses its default (`L2`)."
    )
    @PluginProperty(group = "main")
    private Property<Distance> distance;

    @Schema(
        title = "Collection metadata",
        description = "Arbitrary key/value metadata stored on the collection."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> metadata;

    @Schema(
        title = "Get or create",
        description = "When true (default), returns the existing collection if one with the same name already exists "
            + "instead of failing."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Boolean> getOrCreate = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rCollectionName = runContext.render(this.collectionName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'collectionName' is required"));
        var rDistance = runContext.render(this.distance).as(Distance.class).orElse(null);
        var rGetOrCreate = runContext.render(this.getOrCreate).as(Boolean.class).orElse(true);
        var rMetadata = this.metadata == null
            ? null
            : runContext.render(this.metadata).asMap(String.class, Object.class);

        var body = new LinkedHashMap<String, Object>();
        body.put("name", rCollectionName);
        body.put("get_or_create", rGetOrCreate);
        if (rMetadata != null && !rMetadata.isEmpty()) {
            body.put("metadata", rMetadata);
        }
        if (rDistance != null) {
            body.put("configuration", Map.of("hnsw", Map.of("space", rDistance.space())));
        }

        try (var api = api(runContext)) {
            runContext.logger().info("Creating Chroma collection '{}'", rCollectionName);
            var created = api.request("POST", api.collectionsPath(), body);

            var collectionId = created != null && created.get("id") != null ? created.get("id").asText() : null;

            return Output.builder()
                .collectionName(rCollectionName)
                .collectionId(collectionId)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Collection name", description = "The name of the collection.")
        private final String collectionName;

        @Schema(title = "Collection id", description = "The UUID assigned to the collection by Chroma.")
        private final String collectionId;
    }
}
