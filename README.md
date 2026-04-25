# Elastiflix Loader — Java

A command-line data loader that bulk-ingests a ~30 K movie dataset into an Elasticsearch cluster and creates the `elastiflix-movies` index with semantic search capabilities powered by ELSER and multilingual-E5 embeddings.

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 21+ |
| Maven | 3.8+ |
| Elasticsearch | 9.x (local cluster at `https://localhost:9200`) |

The loader is designed to run against the 4-node Docker Compose cluster defined in the sibling `../docker-compose/` directory. Start that cluster first.

## Configuration

Before running, open `ElasticDataLoader.java` and update the credentials to match your cluster:

```java
final String username = "elastic";
final String password = "your-password-here"; // line 37
```

The password must match the `ELASTIC_PASSWORD` set in `../docker-compose/.env`.

> **Note:** The SSL context trusts all certificates and disables hostname verification. This is intentional for the local dev cluster — do not use this configuration in production.

## Running

```bash
mvn exec:java
```

On first run the loader will:
1. Verify cluster health and print the cluster name and node count.
2. Create the `elastiflix-movies` index (skipped if it already exists).
3. Stream and bulk-ingest all movies in batches of 500, printing progress every batch.

Expected output:
```
Cluster name:     my-cluster
Number of nodes:  4
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

Inference endpoint configurations used to create the ML models are in:

```
src/main/resources/cloud/bigfito/elastic/config/
├── inference_e5.json       # text_embedding — multilingual-e5-small, adaptive 1–32 allocations
├── inference_elser.json    # sparse_embedding — elser_model_2, adaptive 2–32 allocations
└── inference_rerank.json   # rerank — rerank-v1, 2 allocations
```

## Dataset

The movie dataset (`movies.zip`, ~29 MB) is bundled inside the project and unzipped at runtime from the classpath. It contains JSON objects with fields matching the index schema above.

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
    └── movies.zip                  # Bundled dataset (~30K movies)
```

## Dependencies

| Library | Version |
|---|---|
| `co.elastic.clients:elasticsearch-java` | 9.3.3 |
| `org.elasticsearch.client:elasticsearch-rest-client` | 9.3.3 |
| `org.slf4j:slf4j-simple` | 1.7.36 |
