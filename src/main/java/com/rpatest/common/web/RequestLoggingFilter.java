package com.rpatest.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs every API request twice: when it starts (method, URI, caller, remote address) and when it
 * ends (status, duration). Deliberately NOT a {@code @Component}: Boot would register such a bean
 * at the end of the servlet chain, after Spring Security, where the caller is already resolved but
 * 401/403 responses never reach the filter. It is added to the security chain right after
 * {@link com.rpatest.auth.web.JwtAuthenticationFilter} (see {@code SecurityConfig}), so the
 * authenticated user is known and rejected requests are logged too.
 * Only the request line is logged — never headers, cookies or bodies (tokens, passwords).
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String target = describeTarget(request);
        String caller = describeCaller();
        String remoteAddr = request.getRemoteAddr();
        long startedAtNanos = System.nanoTime();
        log.info("--> {} {} from user={} ip={}", request.getMethod(), target, caller, remoteAddr);

        boolean failed = true;
        try {
            filterChain.doFilter(request, response);
            failed = false;
        } finally {
            long tookMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
            if (failed) {
                log.error("<-- {} {} unhandled error, user={} ip={} took {} ms",
                        request.getMethod(), target, caller, remoteAddr, tookMs);
            } else {
                log.info("<-- {} {} status={} user={} ip={} took {} ms",
                        request.getMethod(), target, response.getStatus(), caller, remoteAddr, tookMs);
            }
        }
    }

    private static String describeTarget(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private static String describeCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        return authentication.getName();
    }
}
