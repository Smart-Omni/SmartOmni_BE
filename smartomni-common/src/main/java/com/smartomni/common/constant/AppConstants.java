package com.smartomni.common.constant;

public final class AppConstants {

    private AppConstants() {
    }

    // Header tu client khong duoc tin cay de cap quyen tenant; JWT duoc xac thuc tai Java service.
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";
    public static final String HEADER_TENANT_SUBDOMAIN = "X-Tenant-Subdomain";
    public static final String BEARER_PREFIX = "Bearer ";

    // Role
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_TENANT_ADMIN = "TENANT_ADMIN";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    // Feature flag keys (UC-29 / UC-41)
    public static final String FEATURE_DEMAND_FORECASTING = "demand_forecasting";
    public static final String FEATURE_RECOMMENDATION_ENGINE = "recommendation_engine";
    public static final String FEATURE_PRIORITY_SUPPORT = "priority_support";

    // Redis Key Prefixes
    public static final String REDIS_PREFIX_BLACKLIST = "auth:blacklist:";
    public static final String REDIS_PREFIX_TENANT_LOCKED = "auth:tenant_locked:";
}
