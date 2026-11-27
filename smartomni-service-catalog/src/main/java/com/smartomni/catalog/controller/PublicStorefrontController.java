package com.smartomni.catalog.controller;

import com.smartomni.catalog.dto.PublicStorefrontProductDto;
import com.smartomni.catalog.service.PublicStorefrontCatalogService;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.common.tenant.TenantPlanResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Public storefront catalog: anonymous shoppers browse an ACTIVE store's products and open the marketplace listing.
 *
 * <p>The store comes from the path only. Whatever tenant the Host header or a JWT put in {@link TenantContext} is
 * replaced for the duration of the call, and no role is set, so RLS limits every query to that one store.
 * This controller is deliberately not transactional: the scope must exist before the service transaction starts.
 */
@RestController
@RequestMapping("/api/v1/products/public/by-subdomain/{subdomain}")
@RequiredArgsConstructor
public class PublicStorefrontController {

    private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9-]{3,30}$");

    private final PublicStorefrontCatalogService catalogService;
    private final TenantPlanResolver tenantPlanResolver;

    @GetMapping
    public ApiResponse<Page<PublicStorefrontProductDto>> list(
            @PathVariable String subdomain,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort) {
        return ApiResponse.success(inStore(subdomain, () -> catalogService.listProducts(page, size, search, sort)));
    }

    @GetMapping("/{productId}")
    public ApiResponse<PublicStorefrontProductDto> detail(@PathVariable String subdomain, @PathVariable long productId) {
        return ApiResponse.success(inStore(subdomain, () -> catalogService.getProduct(productId)));
    }

    private <T> T inStore(String rawSubdomain, Supplier<T> work) {
        String subdomain = rawSubdomain == null ? "" : rawSubdomain.trim().toLowerCase(Locale.ROOT);
        if (!SUBDOMAIN.matcher(subdomain).matches()) {
            throw new ResourceNotFoundException("Store not found");
        }
        TenantPlanResolver.TenantLookupResult store = tenantPlanResolver.findTenantBySubdomain(subdomain)
                .filter(t -> "active".equalsIgnoreCase(t.status()))
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
        try (TenantContext.Scope ignored = TenantContext.openScope(store.id(), null, null, store.subdomain())) {
            return work.get();
        }
    }
}
