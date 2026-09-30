package com.eventflow.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Edge-case handler behavior: client mistakes must never surface as 500s.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("unknown routes map to 404, not 500")
    void unknownRouteIs404() {
        ResponseEntity<Map<String, Object>> response
                = handler.handleNoResource(new NoResourceFoundException(null, "nonexistent"));

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

    @Test
    @DisplayName("validation errors are combined once per distinct field message")
    void validationErrorsAreCombined() throws NoSuchMethodException {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError customerError = new FieldError("request", "customerId", "must not be null");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                customerError,
                customerError,
                new FieldError("request", "amount", "amount must be at least 0.01")));
        MethodParameter parameter = new MethodParameter(
                OrderController.class.getMethod("createOrder", OrderRequest.class), 0);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(
                new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("message")).isEqualTo(
                "customerId: must not be null; amount: amount must be at least 0.01");
    }

    @Test
    @DisplayName("validation with no field errors uses the fallback message")
    void emptyValidationErrorsUseFallbackMessage() throws NoSuchMethodException {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());
        MethodParameter parameter = new MethodParameter(
                OrderController.class.getMethod("createOrder", OrderRequest.class), 0);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(
                new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(response.getBody()).containsEntry("message", "validation failed");
    }

    @Test
    @DisplayName("unreadable request bodies map to a bad request")
    void unreadableRequestIs400() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "malformed", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<Map<String, Object>> response = handler.handleUnreadable(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "malformed JSON body");
    }

    @Test
    @DisplayName("unexpected errors return a generic response without internal details")
    void unexpectedErrorDoesNotExposeExceptionMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(
                new IllegalStateException("internal detail"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "Internal Server Error");
        assertThat(response.getBody()).doesNotContainKey("message");
    }
}
