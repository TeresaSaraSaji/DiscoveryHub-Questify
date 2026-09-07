package com.discoveryhub.ingestion.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Caps the size of a {@code POST /messages} body before Jackson materialises it.
 *
 * <p>A cap enforced in the controller arrives too late: the array is already parsed and on the heap
 * by then. {@code Content-Length} is checked first because it is free, but it cannot be the only
 * check — it is absent on a chunked request, where {@code getContentLengthLong()} returns -1 and
 * every {@code -1 > limit} comparison is false. Trusting it alone leaves the limit off entirely for
 * any client that streams, which is the opposite of the guarantee. When the length is unknown the
 * body is therefore read through a counting buffer that stops at the limit, so the worst case is
 * bounded by the limit rather than by what the client feels like sending.
 */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper mapper;

    public RequestSizeFilter(@Value("${discoveryhub.ingestion.max-request-bytes:16777216}") long maxBytes,
                             ObjectMapper mapper) {
        this.maxBytes = maxBytes;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/messages".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            refuse(response, declared + " bytes");
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = readUpToLimit(request.getInputStream());
        if (body == null) {
            refuse(response, "more than " + maxBytes + " bytes");
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    /** @return the body, or null if it runs past the limit */
    private byte[] readUpToLimit(InputStream in) throws IOException {
        int limit = (int) Math.min(maxBytes, Integer.MAX_VALUE - 8L);
        byte[] body = in.readNBytes(limit + 1);
        return body.length > limit ? null : body;
    }

    private void refuse(HttpServletResponse response, String size) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Same shape as every other outcome, so a client has one response format to parse.
        mapper.writeValue(response.getWriter(), IngestResponse.of(List.of(IngestResult.rejected(
                null, "request body of " + size + " exceeds the " + maxBytes
                        + " byte limit; post smaller batches"))));
    }

    /**
     * Replays the already-consumed body downstream. Bounded by {@code maxBytes}, and only used on
     * the path where the client declined to declare a length.
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public int available() {
                    return in.available();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("async reads are not used on this path");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }
}
