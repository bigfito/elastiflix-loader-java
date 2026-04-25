# Elastiflix Loader — Java

A command-line data loader that bulk-ingests a ~30 K movie dataset into an Elasticsearch cluster and creates the `elastiflix-movies` index with semantic search capabilities powered by ELSER and multilingual-E5 embeddings.

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 21+ |
| Maven | 3.8+ |
| Elasticsearch | 9.x |

The loader connects to any Elasticsearch cluster — local, Docker, or Elastic Cloud — via environment variables.

## Configuration

Set the following environment variables before running:

| Variable | Description |
|---|---|
| `ELASTIC_ENDPOINT` | Full URL of your Elasticsearch cluster (e.g. `https://localhost:9200` or your Cloud endpoint) |
| `APIKEY` | Elasticsearch API key |

```bash
export ELASTIC_ENDPOINT="https://your-cluster-url"
export APIKEY="your-api-key-here"
```

> **Note:** The SSL context trusts all certificates and disables hostname verification. This is intentional for local dev — do not use this configuration in production.

## Running

```bash
mvn exec:java
```

By default, only index creation is active. To run the full setup, uncomment the relevant method calls in `ElasticDataLoader.java` (lines 78–79):

```java
createIndex(client);
//ingestData(client);
//createInferenceEndpoints(client);
```

The three operations are independent and can be enabled selectively:

| Method | What it does | Default |
|---|---|---|
| `createIndex()` | Creates the `elastiflix-movies` index from `schema.json` (skipped if it already exists) | **Enabled** |
| `ingestData()` | Bulk-ingests all movies in batches of 500, printing progress every batch | Commented out |
| `createInferenceEndpoints()` | Creates the three ML inference endpoints (skipped if they already exist) | Commented out |

### Recommended setup order

1. Create inference endpoints first (they are referenced by the index schema).
2. Create the index.
3. Ingest data.

### Expected output (full run)

```
Connecting to Elasticsearch at https://your-cluster-url
Creating inference endpoint elastiflix-e5 (TextEmbedding)...
Inference endpoint elastiflix-e5 created successfully.
Creating inference endpoint elastiflix-elser (SparseEmbedding)...
Inference endpoint elastiflix-elser created successfully.
Creating inference endpoint elastiflix-rerank (Rerank)...
Inference endpoint elastiflix-rerank created successfully.
Creating index elastiflix-movies...
Index elastiflix-movies created successfully.
Ingesting data into elastiflix-movies...
Indexed 500 movies...
Indexed 1000 movies...
...
Successfully indexed 30361 movies.
```

## Index Schema

The `elastiflix-movies` index is defined in `src/main/resources/cloud/bigfito/elastic/config/schema.json` and includes:

- **Standard fields:** `title`, `overview`, `cast`, `genres`, `release_date`, `budget`, `revenue`, `popularity`, `vote_average`, `vote_count`, and more.
- **Semantic text fields** (AI-powered):
  - `plot_elser` — sparse embeddings via the ELSER model (best for English keyword-style queries)
  - `plot_e5` — dense embeddings via `multilingual-e5-small` (best for multilingual semantic queries)
  - Both are populated automatically from the `plot` field via `copy_to`.

## Inference Endpoints

Three ML inference endpoints are created by `createInferenceEndpoints()`. Their configurations live in:

```
src/main/resources/cloud/bigfito/elastic/config/
├── inference_e5.json       # elastiflix-e5 — text_embedding, multilingual-e5-small, adaptive 1–32 allocations
├── inference_elser.json    # elastiflix-elser — sparse_embedding, elser_model_2, adaptive 2–32 allocations
└── inference_rerank.json   # elastiflix-rerank — rerank, rerank-v1, 2 allocations
```

## Dataset

The movie dataset (`movies.json`, ~30 K movies) is bundled inside the project and loaded at runtime from the classpath. It contains JSON objects with fields matching the index schema above.

## Project Structure

```
src/main/java/cloud/bigfito/elastic/
└── ElasticDataLoader.java          # Single entry point — all loader logic

src/main/resources/cloud/bigfito/elastic/
├── config/
│   ├── schema.json                 # Elasticsearch index mappings
│   ├── inference_e5.json
│   ├── inference_elser.json
│   └── inference_rerank.json
└── movies/
    └── movies.json                 # Bundled dataset (~30K movies)
```

## Dependencies

| Library | Version |
|---|---|
| `co.elastic.clients:elasticsearch-java` | 9.3.3 |
| `org.elasticsearch.client:elasticsearch-rest-client` | 9.3.3 |
| `org.slf4j:slf4j-simple` | 1.7.36 |
