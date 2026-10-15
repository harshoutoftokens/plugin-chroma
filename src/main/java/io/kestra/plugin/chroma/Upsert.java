package io.kestra.plugin.chroma;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    title = "Upsert records into a Chroma collection",
    description = "Inserts or updates records (id, embedding, and optional metadata and document) in batches. "
        + "Records can be provided inline or read from a file in Kestra's internal storage (ION or JSON)."
)
@Plugin(
    examples = {
        @Example(
            title = "Upsert records inline",
            full = true,
            code = """
                id: chroma_upsert
                namespace: company.team

                tasks:
                  - id: upsert
                    type: io.kestra.plugin.chroma.Upsert
                    baseUrl: http://localhost:8000
                    collectionName: docs
                    from:
                      - id: doc-1
                        embedding: [0.05, 0.61, 0.76, 0.74]
                        document: "Berlin is the capital of Germany."
                        metadata:
                          source: handbook
                      - id: doc-2
                        embedding: [0.19, 0.81, 0.75, 0.11]
                        document: "Paris is the capital of France."
                        metadata:
                          source: handbook
                """
        ),
        @Example(
            title = "Upsert records streamed from an upstream task's output file",
            full = true,
            code = """
                id: chroma_upsert_from_file
                namespace: company.team

                tasks:
                  - id: upsert
                    type: io.kestra.plugin.chroma.Upsert
                    baseUrl: http://localhost:8000
                    collectionName: docs
                    batchSize: 200
                    from: "{{ outputs.embed.uri }}"
                """
        )
    }
)
public class Upsert extends ChromaConnection implements RunnableTask<Upsert.Output>, Data.From {

    @Schema(
        title = "Collection name",
        description = "The name of the collection to upsert into."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> collectionName;

    @Schema(
        title = "Records to upsert",
        description = "An inline list (or single map) of records, or a Kestra storage URI to an ION/JSON file of them. "
            + "Each record must contain an `id` and an `embedding` (a list of numbers; `vector` is accepted as an alias) "
            + "and may contain a `metadata` map and a `document` string.",
        anyOf = {String.class, List.class, Map.class}
    )
    @NotNull
    @PluginProperty(group = "main", dynamic = true)
    private Object from;

    @Schema(
        title = "Batch size",
        description = "Number of records sent per request (default: 100, min: 1, max: 10000)."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(10000) Integer> batchSize = Property.ofValue(100);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rCollectionName = runContext.render(this.collectionName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'collectionName' is required"));
        int rBatchSize = runContext.render(this.batchSize).as(Integer.class).orElse(100);
        // The @Min/@Max on the property is enforced by Kestra at flow execution; this keeps a clear message for the rendered value.
        if (rBatchSize < 1 || rBatchSize > 10000) {
            throw new IllegalArgumentException("'batchSize' must be between 1 and 10000, but was " + rBatchSize + ".");
        }

        try (var api = api(runContext)) {
            var collectionId = api.resolveCollectionId(rCollectionName);
            var path = api.recordPath(collectionId, "upsert");

            long total = 0;
            var batches = Data.from(this.from)
                .read(runContext)
                .buffer(rBatchSize)
                .toIterable();

            for (var batch : batches) {
                if (batch.isEmpty()) {
                    continue;
                }
                api.request("POST", path, toUpsertBody(batch));
                total += batch.size();
                runContext.logger().debug("Upserted batch of {} records (running total {})", batch.size(), total);
            }

            runContext.logger().info("Upserted {} records into collection '{}'", total, rCollectionName);
            return Output.builder().upsertedCount(total).build();
        }
    }

    private static Map<String, Object> toUpsertBody(List<Map<String, Object>> batch) {
        var ids = new ArrayList<String>(batch.size());
        var embeddings = new ArrayList<>(batch.size());
        var metadatas = new ArrayList<>(batch.size());
        var documents = new ArrayList<>(batch.size());
        var anyMetadata = false;
        var anyDocument = false;

        for (var record : batch) {
            var id = record.get("id");
            if (id == null) {
                throw new IllegalArgumentException("Each record must contain an 'id'. Offending record: " + record);
            }
            ids.add(id.toString());

            var embedding = record.containsKey("embedding") ? record.get("embedding") : record.get("vector");
            if (!(embedding instanceof List<?> embeddingList) || embeddingList.isEmpty()) {
                throw new IllegalArgumentException("Record '" + id + "' must contain a non-empty 'embedding' list of numbers.");
            }
            embeddings.add(embeddingList);

            var metadata = record.get("metadata");
            metadatas.add(metadata);
            if (metadata != null) {
                anyMetadata = true;
            }

            var document = record.get("document");
            documents.add(document);
            if (document != null) {
                anyDocument = true;
            }
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("ids", ids);
        body.put("embeddings", embeddings);
        if (anyMetadata) {
            body.put("metadatas", metadatas);
        }
        if (anyDocument) {
            body.put("documents", documents);
        }
        return body;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Upserted count",
            description = "The number of records sent to the collection."
        )
        private final long upsertedCount;
    }
}
