package com.eventflow.fulfillment;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorExceptionHandlerTest {

    private final ErrorExceptionHandler handler = new ErrorExceptionHandler();

    @Test
    void malformedRequestBodyReturnsBadRequest() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "malformed body", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<Map<String, Object>> response = handler.handleUnreadable(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "Bad Request")
                .containsEntry("message", "malformed request body");
    }

    @Test
    void invalidEnumValueListsAcceptedValues() {
        InvalidFormatException invalidFormat = InvalidFormatException.from(
                null, "invalid enum value", "INVALID", FailureInjector.FaultType.class);
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "invalid enum", invalidFormat, new MockHttpInputMessage(new byte[0]));

        ResponseEntity<Map<String, Object>> response = handler.handleUnreadable(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("message").toString())
                .contains("invalid value 'INVALID'")
                .contains("[NONE, ALWAYS, ONCE_PER_EVENT]");
    }

    @Test
    void missingRouteReturnsNotFound() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoResource(
                new NoResourceFoundException(null, "missing"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("message", "no route for /missing");
    }

    @Test
    void methodMismatchReturnsMethodNotAllowed() {
        ResponseEntity<Map<String, Object>> response = handler.handleMethod(
                new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).containsEntry("error", "Method Not Allowed");
    }

        @Test
        void unsupportedMediaTypeReturns415() {
                ResponseEntity<Map<String, Object>> response = handler.handleMediaType(
                                new HttpMediaTypeNotSupportedException("text/plain"));

                assertThat(response.getStatusCode().value()).isEqualTo(415);
                assertThat(response.getBody()).containsEntry("error", "Unsupported Media Type")
                                .containsKey("message")
                                .containsKey("timestamp");
        }

    @Test
    void unexpectedExceptionDoesNotExposeInternalMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(
                new IllegalStateException("internal detail"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "Internal Server Error");
        assertThat(response.getBody()).doesNotContainKey("message");
    }

    @Test
    void missingResourceWithoutPathUsesGenericMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleNoResource(
                new NoResourceFoundException(null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).containsEntry("message", "no such route");
    }
}