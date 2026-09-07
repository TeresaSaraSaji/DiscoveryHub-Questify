package com.discoveryhub.ingestion.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses oversized bodies on {@code Content-Length} alone, before Jackson materialises them.
 *
 * <p>A cap enforced in the controller arrives too late: the array is already parsed and on the
 * heap by then. Checking the declared length first is what actually protects the service from a
 * client that tries to post the whole 10 MB corpus in one request.
 */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeFilter(@Value("${discoveryhub.ingestion.max-request-bytes:16777216}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/messages");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"request body of " + declared + " bytes exceeds the "
                            + maxBytes + " byte limit; post smaller batches\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
