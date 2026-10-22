package com.smartomni.common.tenant;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.security.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Load tenant context only from a verified JWT before the security chain.
 * Never trust client-supplied X-User-Role/X-Tenant-Id for RLS authorization.
 * Webhooks establish a narrowly scoped tenant lookup in their controller.
 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public TenantFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            TenantContext.clear();
            String authorization = request.getHeader(AppConstants.HEADER_AUTHORIZATION);
            if (authorization != null && authorization.startsWith(AppConstants.BEARER_PREFIX)) {
                String token = authorization.substring(AppConstants.BEARER_PREFIX.length());
                if (jwtTokenProvider.validateToken(token)) {
                    TenantContext.setTenantId(jwtTokenProvider.getTenantId(token));
                    TenantContext.setCurrentRole(jwtTokenProvider.getRole(token));
                    TenantContext.setCurrentUserId(jwtTokenProvider.getUserId(token));
                    TenantContext.setCurrentSubdomain(jwtTokenProvider.getSubdomain(token));
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
