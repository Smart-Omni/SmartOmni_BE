package com.smartomni.auth.repository;

import com.smartomni.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailAndTenantId(String email, Long tenantId);

    java.util.List<User> findByTenantId(Long tenantId);

    boolean existsByEmail(String email);
}
