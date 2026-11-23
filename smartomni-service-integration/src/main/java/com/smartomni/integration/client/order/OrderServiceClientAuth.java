package com.smartomni.integration.client.order;

import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.tenant.TenantContext;
import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Authentication for {@link OrderServiceClient} only (referenced from {@code @FeignClient(configuration = ...)},
 * deliberately not a {@code @Configuration} so other Feign clients are unaffected).
 *
 * <p>Inside a user request the caller's Bearer token is forwarded. Background work (webhooks, polling) has no
 * caller JWT, so it sends a short internal token ({@code INTERNAL_SERVICE}, user 0) for the tenant in
 * {@link TenantContext}, the same pattern the order service uses towards inventory. Without a tenant the call is
 * refused rather than sent unauthenticated.
 */
public class OrderServiceClientAuth {

    @Bean
    public RequestInterceptor orderServiceAuthInterceptor(JwtTokenProvider tokens) {
        return request -> {
            String callerAuthorization = callerBearer();
            if (callerAuthorization != null) {
                request.header("Authorization", callerAuthorization);
                return;
            }
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Order service call requires a tenant context");
            }
            request.header("Authorization", "Bearer " + tokens.generateToken(0L, "INTERNAL_SERVICE", tenantId));
        };
    }

    private static String callerBearer() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest current = attributes.getRequest();
        String header = current.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header : null;
    }
}
