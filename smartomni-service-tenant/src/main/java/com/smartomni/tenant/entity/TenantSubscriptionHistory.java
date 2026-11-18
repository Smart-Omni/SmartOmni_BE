package com.smartomni.tenant.entity;

import com.smartomni.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "tenant_subscription_history")
public class TenantSubscriptionHistory extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "old_plan_id")
    private Long oldPlanId;

    @Column(name = "new_plan_id")
    private Long newPlanId;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at")
    private Instant changedAt = Instant.now();
}
