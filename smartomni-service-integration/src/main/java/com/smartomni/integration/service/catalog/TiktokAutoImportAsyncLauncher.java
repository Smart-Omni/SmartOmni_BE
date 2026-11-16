package com.smartomni.integration.service.catalog;

import com.smartomni.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Asynchronous launcher for WF-01 TikTok Shop catalog auto-import after OAuth completion.
 * Propagates {@code tenantId} and {@code role} into {@link TenantContext} on the background thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TiktokAutoImportAsyncLauncher {

    private final TiktokAutoImportService autoImportService;

    @Async
    public void launchAfterOAuth(Long tenantId, Long connectionId, String shopId, String role) {
        if (tenantId == null || connectionId == null) {
            return;
        }
        String effectiveRole = (role == null || role.isBlank()) ? "TENANT_ADMIN" : role;
        log.info("Starting async WF-01 TikTok auto-import after OAuth: tenantId={}, connectionId={}, shopId={}",
                tenantId, connectionId, shopId);
        try (var scope = TenantContext.openScope(tenantId, effectiveRole, null)) {
            autoImportService.autoImportForConnection(connectionId);
        } catch (Exception ex) {
            log.error("Async WF-01 TikTok auto-import failed: tenantId={}, connectionId={}, shopId={}, error={}",
                    tenantId, connectionId, shopId, ex.getMessage(), ex);
        }
    }
}
