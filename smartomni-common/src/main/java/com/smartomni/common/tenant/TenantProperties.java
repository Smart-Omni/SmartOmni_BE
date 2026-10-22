package com.smartomni.common.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Cau hinh Subdomain va Multi-tenancy chung cho toan bo he thong.
 */
@Data
@Component
@ConfigurationProperties(prefix = "smartomni.tenant")
public class TenantProperties {

    /**
     * Root / Base domain cua he thong SaaS (vi du: smartomni.vn).
     */
    private String baseDomain = "smartomni.vn";

    /**
     * Danh sach cac subdomain cam / danh rieng cua he thong khong duoc phep tao tenant
     * va khong duoc phep route nhu tenant binh thuong.
     */
    private Set<String> reservedSubdomains = new LinkedHashSet<>(Set.of(
            "admin", "api", "auth", "app", "www", "static", "mail"
    ));
}
