package org.suvia.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ModelTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-AI-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        UUID traceId = parseOrCreate(request.getHeader(TRACE_HEADER));
        response.setHeader(TRACE_HEADER, traceId.toString());
        try (ModelTraceContext.Scope ignored = ModelTraceContext.openTrace(traceId)) {
            filterChain.doFilter(request, response);
        }
    }

    private UUID parseOrCreate(String candidate) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate);
            } catch (IllegalArgumentException ignored) {
                // Untrusted client correlation IDs must not break the request.
            }
        }
        return UUID.randomUUID();
    }
}
