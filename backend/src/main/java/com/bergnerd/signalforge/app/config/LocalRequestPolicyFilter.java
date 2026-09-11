package com.bergnerd.signalforge.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Component
public class LocalRequestPolicyFilter extends OncePerRequestFilter {

    private static final Set<String> DEVELOPMENT_ORIGINS = Set.of(
            "http://localhost:4200",
            "http://127.0.0.1:4200"
    );
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!UNSAFE_METHODS.contains(request.getMethod()) || isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Unsafe request origin or host\"}");
    }

    private boolean isAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return !"null".equals(origin)
                    && (DEVELOPMENT_ORIGINS.contains(origin) || isSameOrigin(request, origin));
        }

        if (!isLocalHost(request.getServerName())) {
            return false;
        }
        return "DELETE".equals(request.getMethod())
                || hasJsonContentType(request);
    }

    private boolean isSameOrigin(HttpServletRequest request, String origin) {
        try {
            URI uri = URI.create(origin);
            int originPort = uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
            int requestPort = request.getServerPort() == -1
                    ? defaultPort(request.getScheme())
                    : request.getServerPort();
            return uri.getScheme().equalsIgnoreCase(request.getScheme())
                    && uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && originPort == requestPort
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)
                || "::1".equals(host);
    }

    private boolean hasJsonContentType(HttpServletRequest request) {
        try {
            return request.getContentType() != null
                    && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
