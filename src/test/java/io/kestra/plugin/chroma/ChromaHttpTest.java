package io.kestra.plugin.chroma;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

// Verifies the auth headers the plugin actually sends, using a tiny in-JVM HTTP server (no Docker required).
@KestraTest
class ChromaHttpTest {

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;
    private final CopyOnWriteArrayList<Map<String, String>> receivedHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            var headers = new HashMap<String, String>();
            headers.put("X-Chroma-Token", exchange.getRequestHeaders().getFirst("X-Chroma-Token"));
            headers.put("Authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            receivedHeaders.add(headers);

            exchange.getRequestBody().readAllBytes();
            var body = "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"docs\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsTokenHeaderWhenAuthTokenIsSet() throws Exception {
        CreateCollection.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .authToken(Property.ofValue("secret-token"))
            .collectionName(Property.ofValue("docs"))
            .build()
            .run(runContextFactory.of());

        assertThat(receivedHeaders, hasSize(1));
        assertThat(receivedHeaders.get(0).get("X-Chroma-Token"), is("secret-token"));
        assertThat(receivedHeaders.get(0).get("Authorization"), nullValue());
    }

    @Test
    void rendersTheAuthTokenExpressionBeforeSending() throws Exception {
        CreateCollection.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .authToken(Property.ofExpression("{{ 'secret-' ~ 'token' }}"))
            .collectionName(Property.ofValue("docs"))
            .build()
            .run(runContextFactory.of());

        assertThat(receivedHeaders, hasSize(1));
        assertThat(receivedHeaders.get(0).get("X-Chroma-Token"), is("secret-token"));
    }

    @Test
    void sendsBasicAuthHeaderWhenUsernameAndPasswordAreSet() throws Exception {
        CreateCollection.builder()
            .baseUrl(Property.ofValue(baseUrl))
            .username(Property.ofValue("user"))
            .password(Property.ofValue("pass"))
            .collectionName(Property.ofValue("docs"))
            .build()
            .run(runContextFactory.of());

        var expected = "Basic " + Base64.getEncoder()
            .encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedHeaders, hasSize(1));
        assertThat(receivedHeaders.get(0).get("Authorization"), is(expected));
        assertThat(receivedHeaders.get(0).get("X-Chroma-Token"), nullValue());
    }
}
