package com.smartomni.auth.service.impl;

import com.smartomni.auth.repository.UserRepository;
import com.smartomni.auth.service.UserLoginAttemptService;
import com.smartomni.common.persistence.RlsSession;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserLoginAttemptServiceImpl implements UserLoginAttemptService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final long LOCK_DURATION_MINUTES = 15;

    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(Long userId) {
        if (userId == null) {
            return;
        }

        setRlsContext("app.auth_user_id", userId.toString());
        setRlsContext("app.user_role", "super_admin");

        userRepository.findById(userId).ifPresent(user -> {
            int attempts = user.getFailedLoginAttempts() == null ? 1 : user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setLockedUntil(Instant.now().plus(LOCK_DURATION_MINUTES, ChronoUnit.MINUTES));
                log.warn("User id {} has reached {} failed attempts, locked until {}", user.getId(), attempts, user.getLockedUntil());
            } else {
                log.info("User id {} failed login attempt: {}/{}", user.getId(), attempts, MAX_FAILED_ATTEMPTS);
            }
            userRepository.saveAndFlush(user);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFailedAttempts(Long userId) {
        if (userId == null) {
            return;
        }

        setRlsContext("app.auth_user_id", userId.toString());
        setRlsContext("app.user_role", "super_admin");

        userRepository.findById(userId).ifPresent(user -> {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.saveAndFlush(user);
            log.debug("Reset failed login attempts for user id: {}", userId);
        });
    }

    private void setRlsContext(String name, String value) {
        if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
            RlsSession.set(entityManager, name, value);
        }
    }
}
