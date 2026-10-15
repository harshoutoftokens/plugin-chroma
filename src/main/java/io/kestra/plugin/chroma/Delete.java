package io.kestra.plugin.chroma;

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
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete records from a Chroma collection",
    description = "Deletes records by their ids and/or by a metadata (`where`) or document (`whereDocument`) filter. "
        + "At least one of `ids`, `where` or `whereDocument` must be provided."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete specific records by id",
            full = true,
            code = """
                id: chroma_delete_by_id
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.chroma.Delete
                    baseUrl: http://localhost:8000
                    collectionName: docs
                    ids: ["doc-1", "doc-2"]
                """
        ),
        @Example(
            title = "Delete records matching a metadata filter",
            full = true,
            code = """
                id: chroma_delete_by_filter
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.chroma.Delete
                    baseUrl: http://localhost:8000
                    collectionName: docs
                    where:
                      source: handbook
                """
        )
    }
)
public class Delete extends ChromaConnection implements RunnableTask<Delete.Output> {

    @Schema(
        title = "Collection name",
        description = "The name of the collection to delete from."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> collectionName;

    @Schema(
        title = "Record ids",
        description = "Ids of the records to delete."
    )
    @PluginProperty(group = "main")
    private Property<List<Object>> ids;

    @Schema(
        title = "Metadata filter",
        description = "A Chroma `where` filter selecting records to delete by metadata."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> where;

    @Schema(
        title = "Document filter",
        description = "A Chroma `where_document` filter selecting records to delete by document text."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> whereDocument;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rCollectionName = runContext.render(this.collectionName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'collectionName' is required"));
        var rIds = this.ids == null ? null : runContext.render(this.ids).asList(Object.class);
        var rWhere = this.where == null
            ? null
            : runContext.render(this.where).asMap(String.class, Object.class);
        var rWhereDocument = this.whereDocument == null
            ? null
            : runContext.render(this.whereDocument).asMap(String.class, Object.class);

        var hasIds = rIds != null && !rIds.isEmpty();
        var hasWhere = rWhere != null && !rWhere.isEmpty();
        var hasWhereDocument = rWhereDocument != null && !rWhereDocument.isEmpty();
        if (!hasIds && !hasWhere && !hasWhereDocument) {
            throw new IllegalArgumentException("At least one of 'ids', 'where' or 'whereDocument' must be provided.");
        }

        var body = new LinkedHashMap<String, Object>();
        if (hasIds) {
            body.put("ids", rIds.stream().map(Object::toString).toList());
        }
        if (hasWhere) {
            body.put("where", rWhere);
        }
        if (hasWhereDocument) {
            body.put("where_document", rWhereDocument);
        }

        try (var api = api(runContext)) {
            var collectionId = api.resolveCollectionId(rCollectionName);
            runContext.logger().info("Deleting records from collection '{}'", rCollectionName);
            api.request("POST", api.recordPath(collectionId, "delete"), body);

            return Output.builder()
                .requestedIdCount(hasIds ? (long) rIds.size() : null)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Requested id count",
            description = "The number of ids sent for deletion when deleting by `ids`, or null when deleting by filter. "
                + "Chroma does not report how many records actually matched, and the task fails if the request is rejected."
        )
        private final Long requestedIdCount;
    }
}
