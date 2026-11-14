package com.smartomni.auth.repository;

import com.smartomni.auth.entity.StaffInvitation;
import com.smartomni.auth.entity.StaffInvitation.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, Long> {

    List<StaffInvitation> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<StaffInvitation> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByTenantIdAndInvitedEmailIgnoreCaseAndStatus(Long tenantId, String invitedEmail, InvitationStatus status);

    @Query("SELECT s FROM StaffInvitation s "
            + "WHERE s.invitedUserId = :userId OR LOWER(s.invitedEmail) = LOWER(:email) "
            + "ORDER BY s.createdAt DESC")
    List<StaffInvitation> findForInvitee(@Param("userId") Long userId, @Param("email") String email);

    @Query("SELECT s FROM StaffInvitation s "
            + "WHERE s.id = :id AND (s.invitedUserId = :userId OR LOWER(s.invitedEmail) = LOWER(:email))")
    Optional<StaffInvitation> findByIdForInvitee(@Param("id") Long id,
                                                 @Param("userId") Long userId,
                                                 @Param("email") String email);

    @Query("SELECT s FROM StaffInvitation s "
            + "WHERE s.status = :status AND s.id <> :excludeId "
            + "AND (s.invitedUserId = :userId OR LOWER(s.invitedEmail) = LOWER(:email))")
    List<StaffInvitation> findOtherPendingForInvitee(@Param("excludeId") Long excludeId,
                                                     @Param("userId") Long userId,
                                                     @Param("email") String email,
                                                     @Param("status") InvitationStatus status);
}
