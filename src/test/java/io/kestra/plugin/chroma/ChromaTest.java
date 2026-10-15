package io.kestra.plugin.chroma;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

// Base for Chroma integration tests; starts a real Chroma server in a container, so Docker is required.
@KestraTest
@Testcontainers
public abstract class ChromaTest {

    protected static final int CHROMA_PORT = 8000;
    protected static final int DIMENSION = 4;

    @Container
    protected static final GenericContainer<?> chromaContainer =
        new GenericContainer<>(DockerImageName.parse("chromadb/chroma:1.0.0"))
            .withExposedPorts(CHROMA_PORT)
            .waitingFor(Wait.forHttp("/api/v2/heartbeat").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));

    protected static String baseUrl;

    @Inject
    protected RunContextFactory runContextFactory;

    protected final String collectionName = "kestra_" + getClass().getSimpleName().toLowerCase()
        + "_" + Long.toHexString(System.nanoTime());

    @BeforeAll
    static void setBaseUrl() {
        baseUrl = "http://" + chromaContainer.getHost() + ":" + chromaContainer.getMappedPort(CHROMA_PORT);
    }
}
