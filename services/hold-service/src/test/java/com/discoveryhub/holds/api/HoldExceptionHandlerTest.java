package com.discoveryhub.holds.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A blank {@code caseId} or an empty custodian list on {@code POST /holds} is a client error, not
 * a server fault. Without this handler, {@code HoldBuilder}'s {@link IllegalArgumentException}
 * propagates past Spring's default mapping as an unhandled 500.
 */
class HoldExceptionHandlerTest {

    private final HoldExceptionHandler handler = new HoldExceptionHandler();

    @Test
    void mapsIllegalArgumentExceptionTo400() {
        ProblemDetail problem = handler.badRequest(new IllegalArgumentException("caseId is required"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("caseId is required");
        assertThat(problem.getTitle()).isEqualTo("Invalid hold request");
    }
}
