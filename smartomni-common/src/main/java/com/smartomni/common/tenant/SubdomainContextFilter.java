package com.smartomni.common.tenant;

import com.smartomni.common.constant.AppConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Filter trích xuất subdomain từ header X-Tenant-Subdomain hoặc từ Host/X-Forwarded-Host
 * và nạp vào TenantContext. Thiết lập chạy sau TenantFilter (hoặc chạy độc lập cho request công khai).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SubdomainContextFilter extends OncePerRequestFilter {

    private final TenantProperties tenantProperties;

    public SubdomainContextFilter(@org.springframework.beans.factory.annotation.Autowired(required = false) TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties != null ? tenantProperties : new TenantProperties();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String servletPath = request.getServletPath();
        return isBypassed(uri) || isBypassed(servletPath);
    }

    private boolean isBypassed(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/api/v1/auth/") || path.startsWith("/api/auth/")
                || path.equals("/api/v1/auth") || path.equals("/api/auth")
                || path.startsWith("/api/v1/tenants/onboarding") || path.startsWith("/api/tenants/onboarding");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String existingSubdomain = TenantContext.getCurrentSubdomain();
        boolean subdomainSetByThisFilter = false;

        try {
            if (existingSubdomain == null || existingSubdomain.isBlank()) {
                String extractedSubdomain = extractSubdomain(request);
                if (extractedSubdomain != null && isSubdomainAllowed(extractedSubdomain)) {
                    TenantContext.setCurrentSubdomain(extractedSubdomain);
                    subdomainSetByThisFilter = true;
                    log.debug("SubdomainContextFilter resolved subdomain: {}", extractedSubdomain);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            if (subdomainSetByThisFilter) {
                TenantContext.setCurrentSubdomain(null);
            }
        }
    }

    /**
     * Ưu tiên trích xuất từ header X-Tenant-Subdomain, nếu không có thì phân tích từ Host / X-Forwarded-Host.
     */
    public String extractSubdomain(HttpServletRequest request) {
        // 1. Kiểm tra header X-Tenant-Subdomain
        String headerSubdomain = request.getHeader(AppConstants.HEADER_TENANT_SUBDOMAIN);
        if (headerSubdomain != null && !headerSubdomain.isBlank()) {
            return headerSubdomain.trim().toLowerCase(Locale.ROOT);
        }

        // 2. Kiểm tra X-Forwarded-Host hoặc Host
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName();
        }

        return parseSubdomainFromHost(host);
    }

    public String parseSubdomainFromHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }

        String cleanHost = host.trim().toLowerCase(Locale.ROOT);
        // Loại bỏ cổng port nếu có (ví dụ: apple.smartomni.vn:8080 -> apple.smartomni.vn)
        if (cleanHost.contains(":")) {
            cleanHost = cleanHost.substring(0, cleanHost.indexOf(':'));
        }

        String baseDomain = tenantProperties.getBaseDomain();
        if (baseDomain != null && !baseDomain.isBlank()) {
            String normalizedBase = baseDomain.trim().toLowerCase(Locale.ROOT);
            if (cleanHost.endsWith("." + normalizedBase)) {
                String prefix = cleanHost.substring(0, cleanHost.length() - normalizedBase.length() - 1);
                if (prefix.contains(".")) {
                    String[] parts = prefix.split("\\.");
                    return parts[parts.length - 1];
                }
                return prefix;
            }
        }

        // Hỗ trợ .localhost cho môi trường local development (ví dụ: apple.localhost -> apple)
        if (cleanHost.endsWith(".localhost")) {
            String prefix = cleanHost.substring(0, cleanHost.length() - ".localhost".length());
            if (prefix.contains(".")) {
                String[] parts = prefix.split("\\.");
                return parts[parts.length - 1];
            }
            return prefix;
        }

        return null;
    }

    public boolean isSubdomainAllowed(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            return false;
        }
        if (subdomain.length() < 3 || subdomain.length() > 30) {
            return false;
        }
        if (!subdomain.matches("^[a-z0-9-]+$")) {
            return false;
        }

        Set<String> reserved = tenantProperties.getReservedSubdomains();
        if (reserved != null && (reserved.contains(subdomain) || reserved.stream().anyMatch(subdomain::equalsIgnoreCase))) {
            return false;
        }

        return true;
    }
}
