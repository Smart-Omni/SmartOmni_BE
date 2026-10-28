package com.smartomni.auth.entity;

import com.smartomni.common.entity.BaseTenantEntity;
import com.smartomni.common.persistence.LowercasePostgreSQLEnumJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;

import java.time.Instant;

/**
 * Entity Người dùng hệ thống SmartOmni (bảng users).
 * Kế thừa BaseTenantEntity để kế thừa tenant_id và BaseEntity (id, created_at, updated_at).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "users")
public class User extends BaseTenantEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "avatar", length = 500)
    private String avatar;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "user_role")
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @JdbcType(LowercasePostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "user_status")
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "failed_login_attempts")
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    public enum UserRole {
        SUPER_ADMIN,
        ADMIN,
        TENANT_ADMIN,
        MANAGER,
        CUSTOMER
    }

    public enum UserStatus {
        ACTIVE,
        DISABLED
    }
}
