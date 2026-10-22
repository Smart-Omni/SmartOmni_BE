package com.smartomni.tenant.entity;

import org.hibernate.annotations.JdbcType;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import com.smartomni.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * UC-17 (Self-service Onboarding), UC-19 (Khoa/mo tai khoan Tenant), TEN-01.1.
 * Day la entity goc - KHONG ke thua BaseTenantEntity vi chinh no la Tenant.
 */
@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String subdomain;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "tenant_status")
    private TenantStatus status = TenantStatus.ACTIVE;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "locked_reason")
    private String lockedReason;

    @Column(name = "locked_by")
    private Long lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    // TEN-01.1: Common identifier across microservices
    @Transient
    private String tenantId;

    // Contact info
    @Transient
    private String contactEmail;

    @Transient
    private String contactPhone;

    // Storefront configuration
    @Transient
    private String storeName;

    @Transient
    private String logoUrl;

    @Transient
    private String bannerUrl;

    @Transient
    private String primaryColor;

    @Transient
    private String supportPhone;

    @Transient
    private String address;

    // Plan & Feature flags
    @Transient
    private String planType;

    @Transient
    private Integer maxMarketplaceConnections;

    @Transient
    private Integer maxSkus;

    @Transient
    private Boolean aiFeaturesEnabled;

    public String getTenantId() {
        return tenantId != null ? tenantId : (getId() != null ? String.valueOf(getId()) : null);
    }

    public enum TenantStatus { ACTIVE, LOCKED }
}
