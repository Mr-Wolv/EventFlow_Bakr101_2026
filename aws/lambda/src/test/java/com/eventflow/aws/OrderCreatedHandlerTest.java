package com.eventflow.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AWS-path handler. The DynamoDB conditional put is the
 * durable-idempotency contract demonstrated in evidence §11 — these tests pin it:
 * first delivery stores, redelivery of the same eventId is a silent no-op, and a
 * processing error is rethrown so SQS redelivers (at-least-once end to end).
 */
@ExtendWith(MockitoExtension.class)
class OrderCreatedHandlerTest {

    @Mock DynamoDbClient dynamodb;
    @Mock S3Client s3;
    @Mock Context context;
    @Mock LambdaLogger logger;

    private OrderCreatedHandler handler;

    private static final String BODY =
            "{\"eventId\":\"11111111-aaaa-4bbb-8ccc-222222222222\","
                    + "\"eventType\":\"OrderCreated\","
                    + "\"orderId\":\"33333333-dddd-4eee-8fff-444444444444\","
                    + "\"customerId\":\"550e8400-e29b-41d4-a716-446655440000\","
                    + "\"amount\":125.50,"
                    + "\"occurredAt\":\"2026-09-30T18:00:00Z\"}";

    @BeforeEach
    void setUp() {
        handler = new OrderCreatedHandler(dynamodb, s3, "eventflow-orders", "eventflow-order-archive");
        // lenient: the construct-only test does not reach the logger
        org.mockito.Mockito.lenient().when(context.getLogger()).thenReturn(logger);
    }

    private SQSEvent eventWith(String body) {
        SQSMessage msg = new SQSMessage();
        msg.setBody(body);
        msg.setMessageId("mid-1");
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(msg));
        return event;
    }

    @Test
    @DisplayName("first delivery: conditional put to DynamoDB + archive to S3")
    void firstDeliveryStoresAndArchives() {
        handler.handleRequest(eventWith(BODY), context);

        verify(dynamodb).putItem(any(PutItemRequest.class));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("conditional put encodes the durable idempotency contract")
    void conditionalPutIsIdempotentByDesign() {
        handler.handleRequest(eventWith(BODY), context);

        org.mockito.ArgumentCaptor<PutItemRequest> captor =
                org.mockito.ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamodb).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression())
                .isEqualTo("attribute_not_exists(eventId)");
        assertThat(captor.getValue().item().get("eventId").s())
                .isEqualTo("11111111-aaaa-4bbb-8ccc-222222222222");
        assertThat(captor.getValue().item().get("status").s())
                .isEqualTo("FULFILLED_BY_LAMBDA");
    }

    @Test
    @DisplayName("redelivery of a known eventId: duplicate branch, no error")
    void duplicateDeliveryIsSuppressed() {
        when(dynamodb.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().build());

        handler.handleRequest(eventWith(BODY), context);

        // The duplicate is swallowed (that's the point) and the archive still happens.
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("S3 key is orders/<orderId>/<eventId>.json")
    void s3KeyEncodesOrderAndEvent() {
        handler.handleRequest(eventWith(BODY), context);

        org.mockito.ArgumentCaptor<PutObjectRequest> captor =
                org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("eventflow-order-archive");
        assertThat(captor.getValue().key())
                .isEqualTo("orders/33333333-dddd-4eee-8fff-444444444444/11111111-aaaa-4bbb-8ccc-222222222222.json");
    }

    @Test
    @DisplayName("unexpected error is rethrown so SQS redelivers (at-least-once)")
    void errorIsRethrownForRedelivery() {
        when(dynamodb.putItem(any(PutItemRequest.class)))
                .thenThrow(new IllegalStateException("table throttled"));

        assertThatThrownBy(() -> handler.handleRequest(eventWith(BODY), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("table throttled");
    }

    @Test
    @DisplayName("S3 failure also propagates for redelivery")
    void s3FailurePropagates() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("s3 down"));

        assertThatThrownBy(() -> handler.handleRequest(eventWith(BODY), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("s3 down");
    }

    @Test
    @DisplayName("empty batch is a no-op")
    void emptyBatchIsNoop() {
        SQSEvent empty = new SQSEvent();
        empty.setRecords(List.of());

        handler.handleRequest(empty, context);

        verify(dynamodb, never()).putItem(any(PutItemRequest.class));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("multiple records in one batch are all processed")
    void processesEveryRecordInBatch() {
        SQSMessage m1 = new SQSMessage();
        m1.setBody(BODY);
        SQSMessage m2 = new SQSMessage();
        m2.setBody(BODY.replace("11111111", "99999999"));
        SQSEvent batch = new SQSEvent();
        batch.setRecords(List.of(m1, m2));

        handler.handleRequest(batch, context);

        verify(dynamodb, org.mockito.Mockito.times(2)).putItem(any(PutItemRequest.class));
        verify(s3, org.mockito.Mockito.times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("default constructor resolves clients via the standard AWS chain (real-AWS path)")
    void defaultsWithoutEnvironment() {
        // Deliberately network-free: the default chain is lazy — clients are built
        // here, credentials resolve only at first request. On LocalStack the endpoint
        // and test credentials are injected by the platform at deploy time (§11);
        // on real AWS the standard chain takes over unchanged.
        assertThat(new OrderCreatedHandler()).isNotNull();
    }

    @Test
    @DisplayName("explicit-endpoint factory builds clients for LocalStack-style wiring")
    void endpointFactoryBuildsClients() {
        OrderCreatedHandler h = OrderCreatedHandler.forEndpoint("http://localhost:4566", "us-east-1");
        assertThat(h).isNotNull();

        OrderCreatedHandler s = OrderCreatedHandler.standard("us-east-1");
        assertThat(s).isNotNull();
    }

    @Test
    @DisplayName("malformed body: exception propagates so SQS redelivers the poison message")
    void malformedBodyIsRethrownForRedelivery() {
        SQSMessage msg = new SQSMessage();
        msg.setBody("not json at all");
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(msg));

        // Poison-message contract: unreadable JSON counts as an unexpected error —
        // the handler rethrows, the message is not ACKed, and SQS will redeliver
        // (in production, a DLQ with maxReceiveCount bounds the retries, the
        // documented analogue of the Kafka path's orders.DLT).
        assertThatThrownBy(() -> handler.handleRequest(event, context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Lambda processing failed");
        verify(dynamodb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    @DisplayName("context logger is used for both outcome branches")
    void logsOutcomes() {
        handler.handleRequest(eventWith(BODY), context);
        verify(logger, org.mockito.Mockito.atLeastOnce())
                .log(org.mockito.ArgumentMatchers.contains("LAMBDA-STORED"));

        when(dynamodb.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().build());
        handler.handleRequest(eventWith(BODY), context);
        verify(logger, org.mockito.Mockito.atLeastOnce())
                .log(org.mockito.ArgumentMatchers.contains("LAMBDA-DUPLICATE"));
    }
}
