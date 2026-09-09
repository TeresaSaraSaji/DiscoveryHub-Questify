package com.discoveryhub.cases.api;

import com.discoveryhub.cases.lifecycle.CaseReadOnlyException;
import com.discoveryhub.cases.lifecycle.IllegalCaseTransitionException;
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
}
