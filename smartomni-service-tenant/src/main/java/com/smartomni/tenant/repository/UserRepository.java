package com.smartomni.tenant.repository;

import com.smartomni.tenant.entity.TenantUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<TenantUser, Long> {

    Optional<TenantUser> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<TenantUser> findFirstByTenantIdAndRoleOrderByCreatedAtAsc(Long tenantId, TenantUser.UserRole role);
}

