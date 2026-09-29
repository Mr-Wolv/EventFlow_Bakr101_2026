package com.eventflow.fulfillment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Operational endpoints. Deliberately unauthenticated — this project has no auth by
 * scope decision, and these endpoints exist to make the failure demos reproducible.
 * Not exposed outside the cluster (Services are ClusterIP); for the docker compose
 * demo the fault is armed via localhost:8081.
 */
@RestController
@RequestMapping("/__admin")
public class AdminController {

    private final FailureInjector failureInjector;
    private final IdempotentConsumer idempotentConsumer;
    private final OrderFulfillmentStore store;

    public AdminController(FailureInjector failureInjector,
                           IdempotentConsumer idempotentConsumer,
                           OrderFulfillmentStore store) {
        this.failureInjector = failureInjector;
        this.idempotentConsumer = idempotentConsumer;
        this.store = store;
    }

    public record FaultRequest(FailureInjector.FaultType fault) {
    }

    @PostMapping("/failure")
    public Map<String, Object> setFailureMode(@RequestBody FaultRequest request) {
        failureInjector.setFault(request.fault());
        return status("fault mode set");
    }

    @PostMapping("/failure/clear")
    public Map<String, Object> clearFailureMode() {
        failureInjector.setFault(FailureInjector.FaultType.NONE);
        return status("fault cleared");
    }

    @GetMapping("/failure")
    public Map<String, Object> getFailureMode() {
        return status("current fault mode");
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uniqueEventsProcessed", idempotentConsumer.processedCount());
        body.put("ordersFulfilled", store.fulfilledCount());
        body.put("injectedFailures", failureInjector.injectedFailures());
        body.put("faultMode", failureInjector.currentFault().name());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/orders/{orderId}/status")
    public ResponseEntity<Map<String, Object>> orderStatus(@PathVariable UUID orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("status", store.getStatus(orderId).name());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> status(String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("faultMode", failureInjector.currentFault().name());
        body.put("injectedFailures", failureInjector.injectedFailures());
        body.put("note", note);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
