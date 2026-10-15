# Chroma Plugin

Use the Chroma plugin to manage collections and to load, query and delete vector records in [Chroma](https://www.trychroma.com/), an open-source vector database. The tasks talk to a Chroma server over its REST API (v2), so they work with both a self-hosted Chroma server and Chroma Cloud.

## Connection

All tasks inherit common connection properties:
- `baseUrl`: The base URL of the Chroma server, for example `http://localhost:8000` (required).
- `tenant`: The tenant that holds the database (default: `default_tenant`).
- `database`: The database that holds the collection (default: `default_database`).
- `authToken`: A token sent in the `X-Chroma-Token` header, for token-authenticated servers and Chroma Cloud (optional, secret).
- `username` / `password`: Credentials for HTTP basic authentication (optional; `password` is secret).

Record operations (`Upsert`, `Query`, `Delete`) address a collection by name; the plugin resolves the name to its Chroma collection id automatically.

## Tasks

- `CreateCollection`: Creates a collection in the configured tenant and database. Supports a distance metric (`L2`, `COSINE` or `IP`), optional collection metadata, and get-or-create semantics (default: `true`). Chroma infers the vector dimension from the first records added.
- `Upsert`: Inserts or updates records (an `id`, an `embedding`, and optional `metadata` and `document`) in configurable batches. Reads records from an inline list or from a file in Kestra's internal storage (ION or JSON, via `kestra://` URIs).
- `Query`: Runs a nearest-neighbour search with a query `vector`. Supports a metadata filter (`where`), a document filter (`whereDocument`), the number of results (`maxResults`), the fields to include (`include`), and fetch output modes (`FETCH`, `FETCH_ONE`, `STORE`).
- `Delete`: Deletes records by `ids` and/or by a metadata (`where`) or document (`whereDocument`) filter.

## Filters

`Query` and `Delete` accept Chroma's native filter syntax directly:
- `where`: a metadata filter, e.g. `{source: handbook}` for equality, or operators such as `{year: {$gte: 2020}}` and `{source: {$in: [handbook, faq]}}`.
- `whereDocument`: a full-text filter on the stored document, e.g. `{$contains: "capital"}`.
