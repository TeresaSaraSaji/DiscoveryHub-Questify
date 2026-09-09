package com.discoveryhub.holds.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps request-validation failures onto HTTP 400. {@code HoldBuilder} validates a new hold's
 * invariants (non-blank {@code caseId}, at least one custodian) by throwing
 * {@link IllegalArgumentException} — without this handler, that propagates past Spring's default
 * mapping as a 500, indistinguishable from a real server fault.
 */
@RestControllerAdvice
public class HoldExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid hold request");
        return problem;
    }
}
