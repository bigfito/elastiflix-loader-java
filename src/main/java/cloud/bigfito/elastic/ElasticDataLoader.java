package cloud.bigfito.elastic;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.inference.PutRequest;
import co.elastic.clients.elasticsearch.inference.TaskType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.elasticsearch.client.ResponseException;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;

public class ElasticDataLoader {

    public static void main(String[] args) throws Exception {

        // Configure the following environment variables in your intellij idea configuration settings
        String serverUrl = System.getenv("ELASTIC_ENDPOINT");
        String apiKey = System.getenv("API_KEY");

        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalStateException("Environment variable ELASTIC_ENDPOINT is not set.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Environment variable APIKEY is not set.");
        }

        System.out.println("Connecting to Elasticsearch at " + serverUrl);

        // -----------------------------------------------------------
        // STEP 1: An SSL context that trusts ANY certificate.
        // This is fine for a local dev machine, but NEVER do this
        // in production. In production you should import the real
        // CA certificate that Elasticsearch generated for you.
        // -----------------------------------------------------------
        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial((chain, authType) -> true)
                .build();

        // -----------------------------------------------------------
        // STEP 2: Build the low-level REST client. This is the
        // actual HTTP client that talks to Elasticsearch.
        // -----------------------------------------------------------
        RestClient restClient = RestClient
                .builder(HttpHost.create(serverUrl))
                .setDefaultHeaders(new Header[]{
                        new BasicHeader("Authorization", "ApiKey " + apiKey)
                })
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .setSSLContext(sslContext)
                        .setSSLHostnameVerifier((hostname, session) -> true))
                .setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                        .setConnectTimeout(5000)
                        .setSocketTimeout(300000)) // 5 minutes
                .build();

        // -----------------------------------------------------------
        // STEP 3: Wrap the REST client in a "transport" that knows
        // how to serialize/deserialize JSON, then create the
        // high-level ElasticsearchClient.
        // -----------------------------------------------------------
        try (ElasticsearchTransport transport =
                     new RestClientTransport(restClient, new JacksonJsonpMapper())) {

            ElasticsearchClient client = new ElasticsearchClient(transport);

            createInferenceEndpoints(client);
            createIndex(client);
            ingestData(client);

            System.out.println("Data loading complete!");
        }
    }

    private static void ingestData(ElasticsearchClient client) throws Exception {
        String indexName = "elastiflix-movies";
        System.out.println("Ingesting data into " + indexName + "...");

        try (InputStream moviesStream = ElasticDataLoader.class.getResourceAsStream("/cloud/bigfito/elastic/movies/movies.json")) {
            if (moviesStream == null) {
                throw new RuntimeException("Could not find movies.json");
            }

            JacksonJsonpMapper mapper = (JacksonJsonpMapper) client._transport().jsonpMapper();
            JsonParser parser = mapper.jsonProvider().createParser(moviesStream);

            // Consume the opening bracket of the array
            parser.next();

            List<BulkOperation> operations = new ArrayList<>();
            int count = 0;

            while (parser.hasNext()) {
                JsonParser.Event event = parser.next();
                if (event == JsonParser.Event.END_ARRAY) {
                    break;
                }
                
                // We are at START_OBJECT
                JsonValue movieValue = parser.getValue();
                JsonData movie = JsonData.of(movieValue);
                JsonValue idValue = movieValue.asJsonObject().get("id");
                String id = idValue.toString();

                // Remove potential quotes from JsonString.toString()
                if (id.startsWith("\"") && id.endsWith("\"")) {
                    id = id.substring(1, id.length() - 1);
                }

                String finalId = id;
                operations.add(new BulkOperation.Builder()
                        .index(idx -> idx
                                .index(indexName)
                                .id(finalId)
                                .document(movie)
                        ).build());

                count++;
                if (count % 500 == 0) {
                    client.bulk(b -> b.index(indexName).operations(operations));
                    operations.clear();
                    System.out.println("Indexed " + count + " movies...");
                }
            }

            if (!operations.isEmpty()) {
                client.bulk(b -> b.index(indexName).operations(operations));
            }
            System.out.println("Successfully indexed " + count + " movies.");
        }
    }

    private static void createIndex(ElasticsearchClient client) throws Exception {
        String indexName = "elastiflix-movies";

        boolean exists = client.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();

        if (exists) {
            System.out.println("Index " + indexName + " already exists.");
            return;
        }

        System.out.println("Creating index " + indexName + "...");

        try (InputStream mappingStream = ElasticDataLoader.class.getResourceAsStream("/cloud/bigfito/elastic/config/schema.json")) {
            if (mappingStream == null) {
                throw new RuntimeException("Could not find schema.json");
            }

            client.indices().create(CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .withJson(mappingStream)
            ));
        }

        System.out.println("Index " + indexName + " created successfully.");
    }

    private static void createInferenceEndpoints(ElasticsearchClient client) throws Exception {
        String[] endpointIds = {"elastiflix-e5", "elastiflix-elser", "elastiflix-rerank"};
        TaskType[] taskTypes = {TaskType.TextEmbedding, TaskType.SparseEmbedding, TaskType.Rerank};
        String[] configFiles = {
                "/cloud/bigfito/elastic/config/inference_e5.json",
                "/cloud/bigfito/elastic/config/inference_elser.json",
                "/cloud/bigfito/elastic/config/inference_rerank.json"
        };

        for (int i = 0; i < endpointIds.length; i++) {
            String endpointId = endpointIds[i];
            TaskType taskType = taskTypes[i];
            String configFile = configFiles[i];

            System.out.println("Creating inference endpoint " + endpointId + " (" + taskType + ")...");

            try {
                client.inference().get(g -> g.inferenceId(endpointId).taskType(taskType));
                System.out.println("Inference endpoint " + endpointId + " already exists.");
                continue;
            } catch (ElasticsearchException e) {
                if (e.status() != 404) {
                    throw e;
                }
            }

            try (InputStream configStream = ElasticDataLoader.class.getResourceAsStream(configFile)) {
                if (configStream == null) {
                    throw new RuntimeException("Could not find " + configFile);
                }

                client.inference().put(PutRequest.of(p -> p
                        .inferenceId(endpointId)
                        .taskType(taskType)
                        .withJson(configStream)
                ));
            } catch (ResponseException e) {
                if (e.getResponse().getStatusLine().getStatusCode() == 408) {
                    System.out.println("Warning: Timeout while creating inference endpoint " + endpointId + ". The model might still be deploying. Check Elasticsearch logs or stats.");
                } else {
                    throw e;
                }
            }
            System.out.println("Inference endpoint " + endpointId + " created successfully.");
        }
    }
}
