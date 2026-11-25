package com.smartomni.common.security;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.TokenExpiredException;
import com.smartomni.common.tenant.TenantContext;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Đọc Bearer token từ header Authorization, kiểm tra blacklist Redis,
 * xác thực và nạp thông tin user (id, role, tenantId) vào SecurityContext & TenantContext.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String LEGACY_ROLE_STAFF = "STAFF";


    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistValidator tokenBlacklistValidator;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   TokenBlacklistValidator tokenBlacklistValidator,
                                   org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistValidator = tokenBlacklistValidator;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PublicEndpointPaths.isBypassedForJwtFilter(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AppConstants.HEADER_AUTHORIZATION);

        if (header != null && header.startsWith(AppConstants.BEARER_PREFIX)) {
            String token = header.substring(AppConstants.BEARER_PREFIX.length());

            try {
                jwtTokenProvider.parseClaims(token);
            } catch (ExpiredJwtException e) {
                AuthenticationErrorWriter.write(response, new TokenExpiredException("Phiên đăng nhập đã hết hạn"));
                return;
            } catch (Exception e) {
                new JsonAuthenticationEntryPoint().commence(request, response,
                        new BadCredentialsException("Invalid token", e));
                return;
            }

            if (tokenBlacklistValidator != null && tokenBlacklistValidator.isBlacklisted(token)) {
                writeRevoked(response);
                return;
            }

            {
                Long userId = jwtTokenProvider.getUserId(token);
                String role = jwtTokenProvider.getRole(token);
                Long tenantId = jwtTokenProvider.getTenantId(token);
                String subdomain = jwtTokenProvider.getSubdomain(token);

                // STAFF đã đổi tên thành MANAGER: Redis vẫn có thể ghi ACTIVE nên phải chặn theo role.
                if (LEGACY_ROLE_STAFF.equalsIgnoreCase(role)) {
                    writeRevoked(response);
                    return;
                }

                if (!(userId == 0L && "INTERNAL_SERVICE".equals(role))
                        && !hasCurrentAccess(userId, jwtTokenProvider.getTokenVersion(token))) {
                    writeRevoked(response);
                    return;
                }

                // Kiểm tra cờ Redis auth:tenant_locked:<tenantId>
                if (tenantId != null && !AppConstants.ROLE_SUPER_ADMIN.equalsIgnoreCase(role) && isTenantLocked(tenantId)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":false,\"message\":\"Tenant đã bị khóa\",\"code\":\"TENANT_LOCKED\"}");
                    return;
                }

                // Chặn MANAGER chưa thuộc cửa hàng (tenantId == null) truy cập các API dữ liệu của tenant
                if (AppConstants.ROLE_MANAGER.equalsIgnoreCase(role) && tenantId == null && !isAllowedForUnassignedManager(request)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":false,\"message\":\"Tài khoản nhân viên chưa thuộc cửa hàng nào\",\"code\":\"MANAGER_NO_TENANT\"}");
                    return;
                }

                TenantContext.setCurrentUserId(userId);
                TenantContext.setCurrentRole(role);
                if (tenantId != null) {
                    TenantContext.setTenantId(tenantId);
                }
                if (subdomain != null && !subdomain.isBlank()) {
                    TenantContext.setCurrentSubdomain(subdomain);
                }

                var authentication = new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeRevoked(HttpServletResponse response) throws IOException {
        AuthenticationErrorWriter.write(response,
                new BusinessException("Phiên đăng nhập đã bị thu hồi", HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED"));
    }

    private boolean isTenantLocked(Long tenantId) {
        if (stringRedisTemplate == null || tenantId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_TENANT_LOCKED + tenantId));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasCurrentAccess(Long userId, Long tokenVersion) {
        if (stringRedisTemplate == null || userId == null || tokenVersion == null) {
            return false;
        }
        try {
            return UserAccessState.active(tokenVersion).equals(
                    stringRedisTemplate.opsForValue().get(UserAccessState.key(userId)));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAllowedForUnassignedManager(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isEmpty()) {
            path = request.getServletPath();
        }
        if (path == null) {
            return false;
        }
        return "/api/v1/users/me".equals(path)
                || path.startsWith("/api/v1/users/me/")
                || path.startsWith("/api/v1/auth/");
    }
}
