package io.kestra.plugin.chroma;

import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;

public interface ChromaConnectionInterface {
    @Schema(
        title = "Base URL of the Chroma server",
        description = "The base URL of the Chroma HTTP server, for example `http://localhost:8000`."
    )
    Property<String> getBaseUrl();

    @Schema(
        title = "Tenant",
        description = "The Chroma tenant that holds the database (default: `default_tenant`)."
    )
    Property<String> getTenant();

    @Schema(
        title = "Database",
        description = "The Chroma database that holds the collection (default: `default_database`)."
    )
    Property<String> getDatabase();

    @Schema(
        title = "Authentication token",
        description = "Token sent in the `X-Chroma-Token` header. Used by token-authenticated self-hosted servers and by Chroma Cloud. "
            + "Mutually exclusive with `username`/`password`."
    )
    Property<String> getAuthToken();

    @Schema(
        title = "Basic-auth username",
        description = "Username for HTTP basic authentication. Requires `password`."
    )
    Property<String> getUsername();

    @Schema(
        title = "Basic-auth password",
        description = "Password for HTTP basic authentication. Requires `username`."
    )
    Property<String> getPassword();
}
