package io.kestra.plugin.chroma;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    title = "Distance metric",
    description = "Distance function used to compare vectors, mapped to Chroma's HNSW `space` setting."
)
public enum Distance {
    @Schema(title = "Squared L2 (Euclidean) distance")
    L2("l2"),

    @Schema(title = "Cosine distance")
    COSINE("cosine"),

    @Schema(title = "Inner (dot) product")
    IP("ip");

    private final String space;

    Distance(String space) {
        this.space = space;
    }

    // The value Chroma expects in `configuration.hnsw.space`.
    public String space() {
        return this.space;
    }
}
