package io.kestra.plugin.chroma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class ChromaConnection extends Task implements ChromaConnectionInterface {

    protected static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    private static final String DEFAULT_TENANT = "default_tenant";
    private static final String DEFAULT_DATABASE = "default_database";

    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl;

    @PluginProperty(group = "connection")
    protected Property<String> tenant;

    @PluginProperty(group = "connection")
    protected Property<String> database;

    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> authToken;

    @PluginProperty(group = "connection")
    protected Property<String> username;

    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> password;

    /**
     * Opens an authenticated client bound to the configured tenant/database.
     * Callers must close it (it is {@link Closeable}).
     */
    protected ApiClient api(RunContext runContext) throws IllegalVariableEvaluationException {
        String rBaseUrl = runContext.render(this.baseUrl).as(String.class)
            .map(url -> url.replaceAll("/+$", ""))
            .orElseThrow(() -> new IllegalArgumentException("'baseUrl' is required"));
        String rTenant = runContext.render(this.tenant).as(String.class).orElse(DEFAULT_TENANT);
        String rDatabase = runContext.render(this.database).as(String.class).orElse(DEFAULT_DATABASE);
        String rToken = runContext.render(this.authToken).as(String.class).orElse(null);
        String rUsername = runContext.render(this.username).as(String.class).orElse(null);
        String rPassword = runContext.render(this.password).as(String.class).orElse(null);

        String authHeaderName = null;
        String authHeaderValue = null;
        if (rToken != null && !rToken.isBlank()) {
            authHeaderName = "X-Chroma-Token";
            authHeaderValue = rToken;
        } else if (rUsername != null && rPassword != null) {
            authHeaderName = "Authorization";
            authHeaderValue = "Basic " + Base64.getEncoder()
                .encodeToString((rUsername + ":" + rPassword).getBytes(StandardCharsets.UTF_8));
        }

        HttpClient httpClient = new HttpClient(
            runContext,
            HttpConfiguration.builder()
                .allowFailed(Property.ofValue(true))
                .build()
        );

        String collectionsPath = "/api/v2/tenants/" + encode(rTenant)
            + "/databases/" + encode(rDatabase) + "/collections";

        return new ApiClient(httpClient, rBaseUrl, collectionsPath, authHeaderName, authHeaderValue);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Thin REST client for a single tenant/database. One per task execution.
     */
    public static class ApiClient implements Closeable {
        private final HttpClient httpClient;
        private final String baseUrl;
        private final String collectionsPath;
        private final String authHeaderName;
        private final String authHeaderValue;

        ApiClient(HttpClient httpClient, String baseUrl, String collectionsPath, String authHeaderName, String authHeaderValue) {
            this.httpClient = httpClient;
            this.baseUrl = baseUrl;
            this.collectionsPath = collectionsPath;
            this.authHeaderName = authHeaderName;
            this.authHeaderValue = authHeaderValue;
        }

        public String collectionsPath() {
            return this.collectionsPath;
        }

        /**
         * Path for a record-level operation, which Chroma addresses by the collection UUID.
         */
        public String recordPath(String collectionId, String operation) {
            return this.collectionsPath + "/" + collectionId + "/" + operation;
        }

        /**
         * Resolves a collection name to its UUID. Chroma's record operations
         * (add/upsert/query/get/delete/count) are keyed by the collection id, not its name.
         */
        public String resolveCollectionId(String collectionName) throws IOException {
            JsonNode collection;
            try {
                collection = request("GET", this.collectionsPath + "/" + encode(collectionName), null);
            } catch (ChromaApiException e) {
                if (e.statusCode == 404) {
                    throw new IllegalArgumentException("Collection '" + collectionName + "' was not found.", e);
                }
                throw e;
            }
            if (collection == null || collection.get("id") == null) {
                throw new IllegalArgumentException("Collection '" + collectionName + "' was not found.");
            }
            return collection.get("id").asText();
        }

        /**
         * Executes a request against the Chroma server and returns the parsed body (or {@code null} if empty).
         *
         * @param method HTTP method
         * @param path   absolute path starting with {@code /api/v2}
         * @param body   object serialized to JSON, or {@code null} for no body
         */
        public JsonNode request(String method, String path, Object body) throws IOException {
            HttpRequest.HttpRequestBuilder builder = HttpRequest.builder()
                .uri(URI.create(this.baseUrl + path))
                .method(method);

            if (this.authHeaderName != null) {
                builder.addHeader(this.authHeaderName, this.authHeaderValue);
            }
            if (body != null) {
                builder.body(HttpRequest.JsonRequestBody.of(body));
            }

            HttpResponse<String> response;
            try {
                response = this.httpClient.request(builder.build(), String.class);
            } catch (IllegalVariableEvaluationException | io.kestra.core.http.client.HttpClientException e) {
                throw new IOException("Chroma request " + method + " " + path + " failed: " + e.getMessage(), e);
            }

            int code = response.getStatus().getCode();
            String responseBody = response.getBody();
            if (code < 200 || code >= 300) {
                throw new ChromaApiException(method, path, code, responseBody);
            }
            if (responseBody == null || responseBody.isBlank()) {
                return null;
            }
            return MAPPER.readTree(responseBody);
        }

        @Override
        public void close() throws IOException {
            this.httpClient.close();
        }
    }

    /**
     * Raised when Chroma returns a non-2xx status, carrying the code and response body for diagnostics.
     */
    public static class ChromaApiException extends IOException {
        private final int statusCode;

        public ChromaApiException(String method, String path, int statusCode, String body) {
            super("Chroma returned HTTP " + statusCode + " for " + method + " " + path
                + (body == null || body.isBlank() ? "" : ": " + body));
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return this.statusCode;
        }
    }

    /**
     * Writes rows as a {@link FetchType}-controlled output: all rows inline, the first row only,
     * or an ION file in Kestra storage.
     */
    public static FetchOutput buildFetchOutput(RunContext runContext, FetchType fetchType, List<Map<String, Object>> rows) throws IOException {
        return buildFetchOutput(runContext, fetchType, rows, Function.identity());
    }

    public static <T> FetchOutput buildFetchOutput(
        RunContext runContext,
        FetchType fetchType,
        List<T> items,
        Function<T, Map<String, Object>> mapper
    ) throws IOException {
        FetchOutput.FetchOutputBuilder builder = FetchOutput.builder();
        return switch (fetchType) {
            case FETCH_ONE -> builder
                .size(items.isEmpty() ? 0L : 1L)
                .row(items.isEmpty() ? null : mapper.apply(items.getFirst()))
                .build();
            case FETCH -> {
                List<Object> rows = new ArrayList<>(items.size());
                for (T item : items) {
                    rows.add(mapper.apply(item));
                }
                yield builder
                    .size((long) rows.size())
                    .rows(rows)
                    .build();
            }
            case STORE -> {
                File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(tempFile))) {
                    for (T item : items) {
                        FileSerde.write(output, mapper.apply(item));
                    }
                    output.flush();
                }
                yield builder
                    .size((long) items.size())
                    .uri(runContext.storage().putFile(tempFile))
                    .build();
            }
            default -> builder
                .size((long) items.size())
                .build();
        };
    }
}
