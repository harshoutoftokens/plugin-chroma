package io.kestra.plugin.chroma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    // Opens a client bound to the configured tenant/database; callers must close it.
    protected ApiClient api(RunContext runContext) throws IllegalVariableEvaluationException {
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class)
            .map(url -> url.replaceAll("/+$", ""))
            .orElseThrow(() -> new IllegalArgumentException("'baseUrl' is required"));
        var rTenant = runContext.render(this.tenant).as(String.class).orElse(DEFAULT_TENANT);
        var rDatabase = runContext.render(this.database).as(String.class).orElse(DEFAULT_DATABASE);
        var rToken = runContext.render(this.authToken).as(String.class).orElse(null);
        var rUsername = runContext.render(this.username).as(String.class).orElse(null);
        var rPassword = runContext.render(this.password).as(String.class).orElse(null);

        var hasToken = rToken != null && !rToken.isBlank();
        var hasUsername = rUsername != null && !rUsername.isBlank();
        var hasPassword = rPassword != null && !rPassword.isBlank();
        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException(
                "Basic authentication requires both 'username' and 'password', but only one was set.");
        }
        if (hasToken && (hasUsername || hasPassword)) {
            throw new IllegalArgumentException(
                "Provide either 'authToken' or 'username'/'password', not both.");
        }

        String authHeaderName = null;
        String authHeaderValue = null;
        if (hasToken) {
            authHeaderName = "X-Chroma-Token";
            authHeaderValue = rToken;
        } else if (hasUsername) {
            authHeaderName = "Authorization";
            authHeaderValue = "Basic " + Base64.getEncoder()
                .encodeToString((rUsername + ":" + rPassword).getBytes(StandardCharsets.UTF_8));
        }

        var httpClient = new HttpClient(
            runContext,
            HttpConfiguration.builder()
                .allowFailed(Property.ofValue(true))
                .timeout(TimeoutConfiguration.builder()
                    .connectTimeout(Property.ofValue(Duration.ofSeconds(10)))
                    .readIdleTimeout(Property.ofValue(Duration.ofSeconds(60)))
                    .build())
                .build()
        );

        var collectionsPath = "/api/v2/tenants/" + encode(rTenant)
            + "/databases/" + encode(rDatabase) + "/collections";

        return new ApiClient(httpClient, rBaseUrl, collectionsPath, authHeaderName, authHeaderValue);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // Thin REST client for a single tenant/database; one per task execution.
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

        // Record-level operations are addressed by the collection UUID, not its name.
        public String recordPath(String collectionId, String operation) {
            return this.collectionsPath + "/" + collectionId + "/" + operation;
        }

        // Resolves a collection name to the UUID that record operations are keyed by.
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

        // Sends the request and returns the parsed JSON body, or null if the response is empty.
        public JsonNode request(String method, String path, Object body) throws IOException {
            var builder = HttpRequest.builder()
                .uri(URI.create(this.baseUrl + path))
                .method(method);

            if (this.authHeaderName != null) {
                builder.addHeader(this.authHeaderName, this.authHeaderValue);
            }
            if (body != null) {
                builder.body(HttpRequest.JsonRequestBody.of(body));
            }

            // Parse the success body straight off the response stream rather than buffering it into a String first.
            var result = new Object() {
                int code;
                JsonNode node;
                String errorBody;
            };
            try {
                this.httpClient.request(builder.build(), (HttpResponse<InputStream> response) -> {
                    result.code = response.getStatus().getCode();
                    try (var content = response.getBody()) {
                        if (content == null) {
                            return;
                        }
                        if (result.code < 200 || result.code >= 300) {
                            result.errorBody = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                        } else {
                            result.node = MAPPER.readTree(content);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IllegalVariableEvaluationException | io.kestra.core.http.client.HttpClientException e) {
                throw new IOException("Chroma request " + method + " " + path + " failed: " + e.getMessage(), e);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }

            if (result.code < 200 || result.code >= 300) {
                throw new ChromaApiException(method, path, result.code, result.errorBody);
            }
            return result.node;
        }

        @Override
        public void close() throws IOException {
            this.httpClient.close();
        }
    }

    // Raised when Chroma returns a non-2xx status, carrying the code and body for diagnostics.
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

    // Emits rows per FetchType: all inline (FETCH), first only (FETCH_ONE), or an ION file (STORE).
    public static FetchOutput buildFetchOutput(RunContext runContext, FetchType fetchType, List<Map<String, Object>> rows) throws IOException {
        return buildFetchOutput(runContext, fetchType, rows, Function.identity());
    }

    public static <T> FetchOutput buildFetchOutput(
        RunContext runContext,
        FetchType fetchType,
        List<T> items,
        Function<T, Map<String, Object>> mapper
    ) throws IOException {
        var builder = FetchOutput.builder();
        return switch (fetchType) {
            case FETCH_ONE -> builder
                .size(items.isEmpty() ? 0L : 1L)
                .row(items.isEmpty() ? null : mapper.apply(items.getFirst()))
                .build();
            case FETCH -> {
                var rows = new ArrayList<>(items.size());
                for (T item : items) {
                    rows.add(mapper.apply(item));
                }
                yield builder
                    .size((long) rows.size())
                    .rows(rows)
                    .build();
            }
            case STORE -> {
                var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (var output = new BufferedOutputStream(new FileOutputStream(tempFile))) {
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
