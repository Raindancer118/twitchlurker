package de.raindancer118.twitchlurker.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fixed-window limits per client IP; generous for the single user, tight for costly actions. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000;
    private static final int GENERAL_LIMIT = 600;
    private static final int ACTION_LIMIT = 20;

    private record Window(long start, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean action = !"GET".equals(request.getMethod())
                && (path.startsWith("/api/twitch/") || path.startsWith("/api/bot/") || path.startsWith("/login"));
        String key = request.getRemoteAddr() + (action ? "|action" : "|all");
        int limit = action ? ACTION_LIMIT : GENERAL_LIMIT;
        long now = System.currentTimeMillis();
        var w = windows.compute(key, (k, old) -> old == null || now - old.start() > WINDOW_MS
                ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        if (w.count() > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (w.start() + WINDOW_MS - now) / 1000)));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Zu viele Anfragen\"}");
            return;
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() > WINDOW_MS);
        }
        chain.doFilter(request, response);
    }
}
