package com.smartomni.integration.entity;

import org.hibernate.annotations.JdbcType;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import com.smartomni.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * UC-18: Cau hinh ket noi san TMDT (App Key/Secret ma hoa AES-256).
 * UC-40: Co "autoImportProducts" - luu lua chon cua Admin ngay sau khi ket noi.
 */
@Getter
@Setter
@Entity
@Table(name = "marketplace_connections", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "platform", "shop_id"}))
public class MarketplaceConnection extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "platform_type")
    private Platform platform;

    @Column(name = "shop_id")
    private String shopId;

    @Column(name = "shop_name")
    private String shopName;

    @Column(name = "app_key")
    private String appKey;

    @Column(name = "app_secret_encrypted")
    private String appSecretEncrypted; // FR-038: ma hoa AES-256 truoc khi luu

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "connection_status")
    private ConnectionStatus status = ConnectionStatus.CONNECTED;

    @Column(name = "auto_import_products")
    private boolean autoImportProducts = false; // FR-080, FR-081

    // --- TikTok Shop authorization (V10). Token columns hold AES-GCM ciphertext and must never leave the
    // service layer: controllers return DTOs only.
    @Column(name = "seller_open_id")
    private String sellerOpenId;

    @Column(name = "shop_cipher")
    private String shopCipher;

    @Column(name = "shop_region")
    private String shopRegion;

    @Column(name = "seller_type")
    private String sellerType;

    /** Space separated scope names as granted by the platform. */
    @Column(name = "granted_scopes")
    private String grantedScopes;

    @Column(name = "access_token_encrypted")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted")
    private String refreshTokenEncrypted;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;

    /** Compare-and-swap counter, bumped by every token rotation. */
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "connected_at")
    private Instant connectedAt = Instant.now();

    public enum Platform { SHOPEE, TIKTOK_SHOP }
    public enum ConnectionStatus { CONNECTED, ERROR, DISCONNECTED, MISSING_SCOPE, REFRESH_FAILED, REVOKED }
}
