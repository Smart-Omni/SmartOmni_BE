package com.smartomni.integration.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Single-use authorization state. Bound to a tenant, the Tenant Admin who started it and a connection.
 * Only the SHA-256 hash of the state is stored.
 */
@Getter
@Setter
@Entity
@Table(name = "marketplace_authorization_states")
public class MarketplaceAuthorizationState extends BaseTenantEntity {

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "state_hash", nullable = false)
    private String stateHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
