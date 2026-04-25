package cloud.bigfito.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
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

        // -----------------------------------------------------------
        // STEP 1: Credentials for the "elastic" super-user.
        // Replace "your-password-here" with the password shown in
        // your Elasticsearch terminal when you first started it.
        // -----------------------------------------------------------
        final String username = "elastic";
        final String password = "<PASSWORD_HERE>";

        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(username, password)
        );

        // -----------------------------------------------------------
        // STEP 2: An SSL context that trusts ANY certificate.
        // This is fine for a local dev machine, but NEVER do this
        // in production. In production you should import the real
        // CA certificate that Elasticsearch generated for you.
        // -----------------------------------------------------------
        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial((chain, authType) -> true)
                .build();

        // -----------------------------------------------------------
        // STEP 3: Build the low-level REST client. This is the
        // actual HTTP client that talks to Elasticsearch.
        // -----------------------------------------------------------
        RestClient restClient = RestClient
                .builder(new HttpHost("localhost", 9200, "https"))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider)
                        .setSSLContext(sslContext)
                        .setSSLHostnameVerifier((hostname, session) -> true))
                .build();

        // -----------------------------------------------------------
        // STEP 4: Wrap the REST client in a "transport" that knows
        // how to serialize/deserialize JSON, then create the
        // high-level ElasticsearchClient.
        //
        // We use try-with-resources so the resources are closed
        // automatically, even if an exception is thrown.
        // -----------------------------------------------------------
        try (ElasticsearchTransport transport =
                     new RestClientTransport(restClient, new JacksonJsonpMapper())) {

            ElasticsearchClient client = new ElasticsearchClient(transport);

            // -------------------------------------------------------
            // STEP 5: Ask the cluster for its health. The response
            // includes how many nodes the cluster has.
            // -------------------------------------------------------
            HealthResponse health = client.cluster().health();

            int numberOfNodes = health.numberOfNodes();
            String clusterName = health.clusterName();

            System.out.println("Cluster name:     " + clusterName);
            System.out.println("Number of nodes:  " + numberOfNodes);

            createIndex(client);
            ingestData(client);
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
}
