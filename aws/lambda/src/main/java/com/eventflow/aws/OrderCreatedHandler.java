package com.eventflow.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AWS deployment path for EventFlow (LocalStack-validated).
 *
 * SQS queue (from the orders topic bridge) → this Lambda →
 *   1. DynamoDB put with conditionExpression attribute_not_exists(eventId):
 *      the event record itself enforces idempotency, so SQS at-least-once
 *      redelivery is a no-op (unlike the Kafka path's process-local set,
 *      this dedup survives restarts and spans concurrent invocations).
 *   2. S3 put of the raw event body: append-only archive copy.
 *
 * Unexpected errors are rethrown so the message returns to the queue and is
 * redelivered — at-least-once semantics are preserved end to end.
 */
public class OrderCreatedHandler implements RequestHandler<SQSEvent, Void> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DynamoDbClient dynamodb;
    private final S3Client s3;
    private final String table;
    private final String bucket;

    /** Test seam: inject clients and names directly. */
    OrderCreatedHandler(DynamoDbClient dynamodb, S3Client s3, String table, String bucket) {
        this.dynamodb = dynamodb;
        this.s3 = s3;
        this.table = table;
        this.bucket = bucket;
    }

    public OrderCreatedHandler() {
        // LocalStack injects AWS_ENDPOINT_URL; when absent (real AWS) the default
        // credential/endpoint chain applies unchanged.
        String endpoint = System.getenv().getOrDefault("AWS_ENDPOINT_URL", "");
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.table = System.getenv().getOrDefault("TABLE_NAME", "eventflow-orders");
        this.bucket = System.getenv().getOrDefault("ARCHIVE_BUCKET", "eventflow-order-archive");

        OrderCreatedHandler h = endpoint.isEmpty()
                ? standard(region)
                : forEndpoint(endpoint, region);
        this.dynamodb = h.dynamodb;
        this.s3 = h.s3;
    }

    /** Real-AWS construction: default credential/endpoint chain, lazy credentials. */
    static OrderCreatedHandler standard(String region) {
        return new OrderCreatedHandler(
                DynamoDbClient.builder()
                        .httpClient(UrlConnectionHttpClient.create())
                        .region(Region.of(region))
                        .build(),
                S3Client.builder()
                        .httpClient(UrlConnectionHttpClient.create())
                        .region(Region.of(region))
                        .build(),
                System.getenv().getOrDefault("TABLE_NAME", "eventflow-orders"),
                System.getenv().getOrDefault("ARCHIVE_BUCKET", "eventflow-order-archive"));
    }

    /**
     * LocalStack-style construction: explicit endpoint, static test credentials,
     * path-style S3. The three env vars are what the LocalStack platform injects
     * into the function; on real AWS this factory is simply never used.
     */
    static OrderCreatedHandler forEndpoint(String endpoint, String region) {
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        System.getenv().getOrDefault("AWS_ACCESS_KEY_ID", "test"),
                        System.getenv().getOrDefault("AWS_SECRET_ACCESS_KEY", "test")));
        return new OrderCreatedHandler(
                DynamoDbClient.builder()
                        .httpClient(UrlConnectionHttpClient.create())
                        .region(Region.of(region))
                        .endpointOverride(URI.create(endpoint))
                        .credentialsProvider(creds)
                        .build(),
                S3Client.builder()
                        .httpClient(UrlConnectionHttpClient.create())
                        .region(Region.of(region))
                        .endpointOverride(URI.create(endpoint))
                        .credentialsProvider(creds)
                        .serviceConfiguration(S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build())
                        .build(),
                System.getenv().getOrDefault("TABLE_NAME", "eventflow-orders"),
                System.getenv().getOrDefault("ARCHIVE_BUCKET", "eventflow-order-archive"));
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        for (SQSMessage msg : event.getRecords()) {
            try {
                JsonNode body = JSON.readTree(msg.getBody());
                String eventId = body.path("eventId").asText(UUID.randomUUID().toString());
                String orderId = body.path("orderId").asText("unknown");
                String customerId = body.path("customerId").asText("unknown");
                String amount = body.path("amount").asText("0");
                String occurredAt = body.path("occurredAt").asText("");

                Map<String, AttributeValue> item = new HashMap<>();
                item.put("eventId", AttributeValue.builder().s(eventId).build());
                item.put("orderId", AttributeValue.builder().s(orderId).build());
                item.put("customerId", AttributeValue.builder().s(customerId).build());
                item.put("amount", AttributeValue.builder().s(amount).build());
                item.put("occurredAt", AttributeValue.builder().s(occurredAt).build());
                item.put("status", AttributeValue.builder().s("FULFILLED_BY_LAMBDA").build());
                item.put("processedAt", AttributeValue.builder().s(Instant.now().toString()).build());

                try {
                    dynamodb.putItem(PutItemRequest.builder()
                            .tableName(table)
                            .item(item)
                            .conditionExpression("attribute_not_exists(eventId)")
                            .build());
                    logger.log("[LAMBDA-STORED] event " + eventId + " order " + orderId + " -> " + table);
                } catch (ConditionalCheckFailedException e) {
                    logger.log("[LAMBDA-DUPLICATE] event " + eventId + " already stored — skipping");
                }

                String key = "orders/" + orderId + "/" + eventId + ".json";
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromBytes(msg.getBody().getBytes(StandardCharsets.UTF_8)));
                logger.log("[LAMBDA-ARCHIVED] s3://" + bucket + "/" + key);
            } catch (RuntimeException e) {
                logger.log("[LAMBDA-ERROR] messageId " + msg.getMessageId() + ": " + e);
                throw e; // return to queue → at-least-once redelivery
            } catch (Exception e) {
                logger.log("[LAMBDA-ERROR] messageId " + msg.getMessageId() + ": " + e);
                throw new RuntimeException("Lambda processing failed: " + e.getMessage(), e);
            }
        }
        return null;
    }
}
