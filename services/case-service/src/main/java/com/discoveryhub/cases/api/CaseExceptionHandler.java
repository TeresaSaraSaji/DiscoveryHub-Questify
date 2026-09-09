package com.discoveryhub.cases.api;

import com.discoveryhub.cases.lifecycle.CaseReadOnlyException;
import com.discoveryhub.cases.lifecycle.IllegalCaseTransitionException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the case-service's domain exceptions onto HTTP. An illegal transition and a mutation on a
 * closed case are both 409 Conflict — the request conflicts with the case's current state, not
 * with the resource's existence — so the caller gets a clear, machine-parseable reason rather than
 * the framework's default error page.
 */
@RestControllerAdvice
public class CaseExceptionHandler {

    @ExceptionHandler(IllegalCaseTransitionException.class)
    public ProblemDetail illegalTransition(IllegalCaseTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Illegal case transition");
        problem.setProperty("from", ex.from().name());
        problem.setProperty("to", ex.to().name());
        return problem;
    }

    @ExceptionHandler(CaseReadOnlyException.class)
    public ProblemDetail readOnly(CaseReadOnlyException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Case is read-only");
        problem.setProperty("caseId", ex.caseId());
        return problem;
    }

    /**
     * {@code CaseBuilder} validates a new/updated case's invariants (non-blank name/owner, a
     * matter type) by throwing {@link IllegalArgumentException} — without this handler, that
     * propagates past Spring's default mapping as an unhandled 500, indistinguishable from a
     * real server fault. Bad input from the caller is a 400.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid case request");
        return problem;
    }

    /**
     * M1 fix: {@code CaseEntity.version} turns a lost update between two concurrent requests on
     * the same case into this exception instead of a silent overwrite. 409, not 500 — the
     * request conflicted with a concurrent change, and retrying against the current state is the
     * right recovery, same as the other conflict cases this handler maps.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail concurrentModification(OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "case was modified concurrently; reload and retry");
        problem.setTitle("Concurrent modification");
        return problem;
    }
}
