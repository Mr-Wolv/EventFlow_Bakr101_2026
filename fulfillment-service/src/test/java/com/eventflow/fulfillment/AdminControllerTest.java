package com.eventflow.fulfillment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private IdempotentConsumer idempotentConsumer;

    @Mock
    private OrderFulfillmentStore store;

    private FailureInjector failureInjector;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        failureInjector = new FailureInjector();
        controller = new AdminController(failureInjector, idempotentConsumer, store);
    }

    @Test
    void failureModeCanBeSetReadAndCleared() {
        Map<String, Object> setResponse = controller.setFailureMode(
                new AdminController.FaultRequest(FailureInjector.FaultType.ALWAYS));

        assertThat(failureInjector.currentFault()).isEqualTo(FailureInjector.FaultType.ALWAYS);
        assertThat(setResponse).containsEntry("faultMode", "ALWAYS")
                .containsEntry("note", "fault mode set")
                .containsEntry("injectedFailures", 0);
        assertThat(Instant.parse((String) setResponse.get("timestamp"))).isNotNull();

        assertThat(controller.getFailureMode()).containsEntry("faultMode", "ALWAYS")
                .containsEntry("note", "current fault mode");

        Map<String, Object> clearResponse = controller.clearFailureMode();
        assertThat(failureInjector.currentFault()).isEqualTo(FailureInjector.FaultType.NONE);
        assertThat(clearResponse).containsEntry("faultMode", "NONE")
                .containsEntry("note", "fault cleared");
    }

    @Test
    void statsAndOrderStatusExposeCurrentState() {
        UUID orderId = UUID.randomUUID();
        failureInjector.setFault(FailureInjector.FaultType.ONCE_PER_EVENT);
        when(idempotentConsumer.processedCount()).thenReturn(3);
        when(store.fulfilledCount()).thenReturn(2L);
        when(store.getStatus(orderId)).thenReturn(OrderStatus.FULFILLED);

        Map<String, Object> stats = controller.stats();
        ResponseEntity<Map<String, Object>> response = controller.orderStatus(orderId);

        assertThat(stats).containsEntry("uniqueEventsProcessed", 3)
                .containsEntry("ordersFulfilled", 2L)
                .containsEntry("injectedFailures", 0)
                .containsEntry("faultMode", "ONCE_PER_EVENT");
        assertThat(Instant.parse((String) stats.get("timestamp"))).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("orderId", orderId)
                .containsEntry("status", "FULFILLED");
    }
}