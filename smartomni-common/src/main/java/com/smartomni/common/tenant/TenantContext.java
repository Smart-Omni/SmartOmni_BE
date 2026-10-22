package com.smartomni.common.tenant;

/**
 * Luu tru tenant_id cua request hien tai trong pham vi 1 thread.
 * Duoc set boi TenantFilter (o Gateway hoac tung service) va duoc doc
 * o tang Repository/Service de dam bao moi truy van chi lay du lieu
 * cua dung Tenant (khong bao gio truyen tenant_id qua tham so thu cong).
 */
public class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_SUBDOMAIN = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static Long getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void setCurrentUserId(Long userId) {
        CURRENT_USER.set(userId);
    }

    public static Long getCurrentUserId() {
        return CURRENT_USER.get();
    }

    public static void setCurrentRole(String role) {
        CURRENT_ROLE.set(role);
    }

    public static String getCurrentRole() {
        return CURRENT_ROLE.get();
    }

    public static void setCurrentSubdomain(String subdomain) {
        CURRENT_SUBDOMAIN.set(subdomain);
    }

    public static String getCurrentSubdomain() {
        return CURRENT_SUBDOMAIN.get();
    }

    public static boolean isSuperAdmin() {
        return "SUPER_ADMIN".equalsIgnoreCase(CURRENT_ROLE.get());
    }

    /** Restore the previous context after background work, including exceptions. */
    public static Scope openScope(Long tenantId, String role, Long userId) {
        return openScope(tenantId, role, userId, null);
    }

    public static Scope openScope(Long tenantId, String role, Long userId, String subdomain) {
        Scope scope = new Scope(getTenantId(), getCurrentRole(), getCurrentUserId(), getCurrentSubdomain());
        clear();
        setTenantId(tenantId);
        setCurrentRole(role);
        setCurrentUserId(userId);
        setCurrentSubdomain(subdomain);
        return scope;
    }

    public record Scope(Long tenantId, String role, Long userId, String subdomain) implements AutoCloseable {
        public Scope(Long tenantId, String role, Long userId) {
            this(tenantId, role, userId, null);
        }

        @Override
        public void close() {
            clear();
            setTenantId(tenantId);
            setCurrentRole(role);
            setCurrentUserId(userId);
            setCurrentSubdomain(subdomain);
        }
    }

    /** Bat buoc goi trong finally block cua Filter de tranh leak ThreadLocal giua cac request (thread pool tai su dung). */
    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_ROLE.remove();
        CURRENT_USER.remove();
        CURRENT_SUBDOMAIN.remove();
    }
}
