# Kestra Chroma Plugin

## What

- Provides tasks under `io.kestra.plugin.chroma` that drive a [Chroma](https://www.trychroma.com/) embedding
  (vector) database over its v2 REST API, against self-hosted Chroma or Chroma Cloud.
- Four runnable tasks:
  - `CreateCollection` – create or get-or-create a collection, with an optional distance metric and metadata.
  - `Upsert` – insert/update records (id, embedding, optional document and metadata) in batches, inline or streamed
    from a Kestra storage file.
  - `Query` – nearest-neighbour search with optional `where`/`whereDocument` filters, returned via `FetchType`
    (inline, first-only, or an ION file in Kestra storage).
  - `Delete` – delete records by id and/or by a metadata or document filter.

## Why

- Chroma is a common vector store for semantic search and retrieval-augmented generation (RAG). Teams need to build
  and refresh those collections as part of a scheduled or event-driven pipeline.
- This plugin makes Chroma a first-class step in a Kestra flow, so embedding, indexing, querying, and cleanup run
  alongside the rest of the data workflow instead of in bespoke scripts.

## How

### Architecture

Single-module plugin. All classes live under `io.kestra.plugin.chroma`:

- `ChromaConnection` / `ChromaConnectionInterface` – shared connection properties (`baseUrl`, `tenant`, `database`,
  auth) and a thin HTTP `ApiClient` used by every task.
- `CreateCollection`, `Upsert`, `Query`, `Delete` – the runnable tasks.
- `Distance` – the distance-metric enum mapped to Chroma's HNSW `space`.

### Testing

- Integration tests spin up a real Chroma server with Testcontainers (`ChromaTest` base class), so Docker is required
  to run the Docker-gated tests.
- `ChromaLifecycleTest` exercises the full create → upsert → query → delete lifecycle; `ChromaValidationTest` covers
  input-validation and expression-rendering cases that run without Docker.

### Project Structure

```
plugin-chroma/
├── src/main/java/io/kestra/plugin/chroma/
├── src/test/java/io/kestra/plugin/chroma/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented tasks and classes, not on template text.
- Keep comments and Javadoc minimal; prefer clear names over narration.

## References

- https://docs.trychroma.com/
- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
