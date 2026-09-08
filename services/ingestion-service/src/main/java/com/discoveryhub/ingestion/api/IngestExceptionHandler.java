package com.discoveryhub.ingestion.api;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Keeps every response from {@code POST /messages} an {@link IngestResponse}.
 *
 * <p>Without this, a body that is not a JSON array at all falls through to the framework's default
 * error page: a different shape, a different content type, and no {@code reason} a client can act
 * on. A loader pushing 250-message batches needs one parseable answer for every outcome, including
 * the ones it caused itself.
 */
@RestControllerAdvice(assignableTypes = IngestController.class)
class IngestExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<IngestResponse> unreadableBody(HttpMessageNotReadableException e) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        return refuse(HttpStatus.BAD_REQUEST,
                "body is not a readable JSON array of messages: " + firstLineOf(cause.getMessage()));
    }

    private static ResponseEntity<IngestResponse> refuse(HttpStatus status, String reason) {
        return ResponseEntity.status(status)
                .body(IngestResponse.of(List.of(IngestResult.rejected(null, reason))));
    }

    private static String firstLineOf(String message) {
        return message == null ? "no detail" : message.lines().findFirst().orElse("no detail");
    }
}
