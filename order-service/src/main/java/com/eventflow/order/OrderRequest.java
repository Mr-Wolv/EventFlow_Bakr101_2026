package com.eventflow.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** POST /orders request body. Validated before the service layer is reached. */
public record OrderRequest(
        @NotNull UUID customerId,
        @NotNull @DecimalMin(value = "0.01", message = "amount must be at least 0.01") BigDecimal amount) {
}
