package com.smartomni.integration.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;

import java.time.Instant;

/**
 * Log toàn bộ Webhook đến (cả tạo đơn lẫn đổi trạng thái), audit và xác thực HMAC-SHA256 (FR-058, FR-059).
 */
@Getter
@Setter
@Entity(name = "IntegrationWebhookEventLog")
@Table(name = "webhook_events_log", uniqueConstraints = @UniqueConstraint(name = "uq_webhook_events_log", columnNames = {"tenant_id", "platform", "event_id"}))
public class WebhookEventLog extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "platform_type")
    private MarketplaceConnection.Platform platform = MarketplaceConnection.Platform.TIKTOK_SHOP;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "queued")
    private boolean queued = false;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "received_at")
    private Instant receivedAt = Instant.now();
}
