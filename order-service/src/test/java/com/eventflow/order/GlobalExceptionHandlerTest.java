package com.eventflow.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Edge-case handler behavior: client mistakes must never surface as 500s. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("unknown routes map to 404, not 500")
    void unknownRouteIs404() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleNoResource(new NoResourceFoundException(null, "nonexistent"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("error", "Not Found");
    }

    @Test
    @DisplayName("a malformed path variable maps to 400 with the offending value named")
    void malformedPathVariableIs400() {
        MethodParameter parameter = new MethodParameter(
                Order.class.getConstructors()[0], 0);
        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException("not-a-uuid", UUID.class, "id", parameter, null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "invalid value 'not-a-uuid' for parameter 'id'");
    }

    @Test
    @DisplayName("wrong HTTP method maps to 405")
    void wrongMethodIs405() {
        ResponseEntity<Map<String, Object>> response = handler.handleMethod(
                new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
    }

    @Test
    @DisplayName("unsupported media type maps to 415")
    void unsupportedMediaTypeIs415() {
        ResponseEntity<Map<String, Object>> response = handler.handleMediaType(
                new HttpMediaTypeNotSupportedException("text/plain"));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
    }
}
