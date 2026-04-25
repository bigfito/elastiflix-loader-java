| Endpoint ID | Type | Required for |
|-------------|------|-------------|
| `elser` | `sparse_embedding` | Semantic (ELSER), Hybrid |
| `e5` | `text_embedding` | Semantic (E5) |

Create them in Kibana Dev Tools:

```http
PUT _inference/sparse_embedding/elser
{
  "service": "elasticsearch",
  "service_settings": {
    "model_id": ".elser-model-2",
    "num_allocations": 1,
    "num_threads": 1
  }
}
```

```http
PUT _inference/text_embedding/e5
{
  "service": "elasticsearch",
  "service_settings": {
    "model_id": ".multilingual-e5-small",
    "num_allocations": 1,
    "num_threads": 1
  }
}
```

BM25 mode works without any inference endpoints.

---

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
elasticsearch:
  host: https://localhost:9200
  api-key: <your-api-key>
  index: elastiflix-movies
  ssl-verify: false        # set to true in production with a valid certificate

app:
  page-size: 50
  tmdb-image-base: https://image.tmdb.org/t/p/w500
```

| Property | Description |
|----------|-------------|
| `elasticsearch.host` | Full URL including scheme and port |
| `elasticsearch.api-key` | Base64-encoded API key from Kibana → Stack Management → API Keys |
| `elasticsearch.ssl-verify` | Set `false` for self-signed certs (local dev only) |
| `app.page-size` | Number of results per page |

---

## Running the Application

```bash
./mvnw spring-boot:run
```

Then open [http://localhost:8080](http://localhost:8080).

To build a self-contained JAR:

```bash
./mvnw clean package
java -jar target/elastiflix-java-0.0.1-SNAPSHOT.jar
```

---

## Project Structure

```
src/main/java/com/elastiflix/
├── config/
│   ├── AppProperties.java          # Binds application.yml properties
│   └── ElasticsearchConfig.java    # ElasticsearchClient bean (API key + optional trust-all SSL)
├── controller/
│   ├── api/
│   │   └── MovieApiController.java # REST endpoints (JSON)
│   └── web/
│       ├── HomeController.java     # GET /
│       ├── SearchController.java   # GET /search
│       └── MovieDetailController.java # GET /movie/{id}
├── exception/
│   └── GlobalExceptionHandler.java
├── model/
│   ├── Movie.java                  # Document model
│   ├── SearchMode.java             # Enum: BM25, ELSER, E5, HYBRID
│   └── SearchResponse.java         # Pagination wrapper
├── repository/
│   └── MovieRepository.java        # All four Elasticsearch query strategies
└── service/
    └── MovieService.java

src/main/resources/
├── application.yml
└── templates/
    ├── index.html                  # Landing page
    ├── search.html                 # Search results + pagination
    ├── movie-detail.html           # Movie detail page
    ├── error.html
    └── fragments/
        ├── layout.html             # Base Thymeleaf layout
        └── movie-card.html         # Reusable movie card component
```

---

## Search Implementation

### BM25
Uses the Elasticsearch Java client fluent builder with a `multi_match` query:
```java
esClient.search(s -> s
    .index(index).from(from).size(size)
    .query(q -> q.multiMatch(mm -> mm
        .query(queryText)
        .fields(List.of("title^3", "original_title^2", "overview", "plot")))),
    Movie.class);
```

### Semantic (ELSER / E5)
Uses `withJson()` to send a raw `semantic` query (ES 8.8+):
```json
{
  "query": {
    "semantic": {
      "field": "plot_elser",
      "query": "<user query>"
    }
  }
}
```

### Hybrid (BM25 + ELSER)
Uses the ES 8.14+ `retriever.rrf` API via `withJson()`:
```json
{
  "retriever": {
    "rrf": {
      "retrievers": [
        { "standard": { "query": { "multi_match": { ... } } } },
        { "standard": { "query": { "semantic": { "field": "plot_elser", ... } } } }
      ],
      "rank_window_size": 100,
      "rank_constant": 60
    }
  }
}
```

---

## Index Mapping

The `elastiflix-movies` index uses `semantic_text` fields that are populated automatically by Elasticsearch at ingest time:

| Field | Type | Used by |
|-------|------|---------|
| `title`, `original_title` | `text` | BM25 |
| `overview`, `plot` | `text` | BM25 |
| `plot_elser` | `semantic_text` (ELSER) | Semantic ELSER, Hybrid |
| `plot_e5` | `semantic_text` (E5) | Semantic E5 |

---

## Related

- **[elastiflix-loader-java](../elastiflix-loader-java)** — data loader that creates the index and bulk-ingests the movie dataset
