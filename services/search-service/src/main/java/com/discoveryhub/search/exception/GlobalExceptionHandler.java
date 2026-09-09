package com.discoveryhub.search.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * One error shape for every failure out of {@code /search}, so a client has one parser for the bad
 * outcomes too — not the framework's default error page, which is a different content type and a
 * body a loader cannot act on.
 *
 * <p>Validation failures are 400 with the offending fields named; a malformed body is 400; a
 * {@link SearchException} keeps the status the thrower chose; everything else is 500 and logged,
 * because an unhandled exception in a read-only search path is a bug, not a client fault.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SearchException.class)
    ResponseEntity<ErrorResponse> searchException(SearchException e) {
        return ResponseEntity.status(HttpStatus.resolve(e.status()) == null ? HttpStatus.INTERNAL_SERVER_ERROR
                        : HttpStatus.valueOf(e.status()))
                .body(new ErrorResponse(e.status(), reasonFor(e.status()), e.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        List<String> details = e.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "Bad Request", "request validation failed", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "Bad Request", "request body is not readable JSON", List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unhandled(Exception e) {
        log.error("unhandled exception in search path", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal Server Error", "unexpected error", List.of()));
    }

    private static String reasonFor(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }

    /** The single error body a client sees for any failure. */
    public record ErrorResponse(int status, String error, String message, List<String> details) {
    }
}
