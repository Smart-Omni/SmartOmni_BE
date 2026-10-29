package com.smartomni.auth.repository;

import com.smartomni.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailAndTenantId(String email, Long tenantId);

    java.util.List<User> findByTenantId(Long tenantId);

    boolean existsByEmail(String email);

    /**
     * Tìm kiếm user theo keyword (email, fullName, phone), status, role và tenantId với phân trang.
     * Nếu tenantId là null thì lấy toàn bộ users (dành cho SUPER_ADMIN).
     */
    @Query("SELECT u FROM User u WHERE " +
           "(:tenantId IS NULL OR u.tenantId = :tenantId) AND " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           "  LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "  LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "  LOWER(u.phone) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
           "(:status IS NULL OR CAST(u.status AS string) = :status) AND " +
           "(:role IS NULL OR CAST(u.role AS string) = :role)")
    Page<User> searchUsers(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("role") String role,
            Pageable pageable
    );
}
