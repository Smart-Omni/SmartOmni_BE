package com.smartomni.auth.service;

import com.smartomni.auth.entity.User;
import com.smartomni.auth.repository.UserRepository;
import com.smartomni.auth.service.impl.UserLoginAttemptServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserLoginAttemptServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EntityManager entityManager;

    private UserLoginAttemptServiceImpl loginAttemptService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        loginAttemptService = new UserLoginAttemptServiceImpl(userRepository, entityManager);

        sampleUser = User.builder()
                .email("test@smartshop.vn")
                .passwordHash("hashed")
                .role(User.UserRole.ADMIN)
                .status(User.UserStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build();
        ReflectionTestUtils.setField(sampleUser, "id", 1L);
    }

    @Test
    @DisplayName("Ghi nhận số lần đăng nhập sai tăng dần khi chưa đạt ngưỡng khóa")
    void testRecordFailedAttempt_IncrementsAttempts() {
        sampleUser.setFailedLoginAttempts(2);
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

        loginAttemptService.recordFailedAttempt(1L);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).saveAndFlush(captor.capture());

        User savedUser = captor.getValue();
        assertEquals(3, savedUser.getFailedLoginAttempts());
        assertNull(savedUser.getLockedUntil());
    }

    @Test
    @DisplayName("Khóa tài khoản và gán lockedUntil khi đạt tối đa 5 lần đăng nhập sai")
    void testRecordFailedAttempt_LocksAccountAtMaxAttempts() {
        sampleUser.setFailedLoginAttempts(4);
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

        loginAttemptService.recordFailedAttempt(1L);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).saveAndFlush(captor.capture());

        User savedUser = captor.getValue();
        assertEquals(5, savedUser.getFailedLoginAttempts());
        assertNotNull(savedUser.getLockedUntil());
        assertTrue(savedUser.getLockedUntil().isAfter(Instant.now()));
    }

    @Test
    @DisplayName("Ghi nhận lần thử sai đầu tiên khi failedLoginAttempts là null")
    void testRecordFailedAttempt_NullInitialAttempts() {
        sampleUser.setFailedLoginAttempts(null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

        loginAttemptService.recordFailedAttempt(1L);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).saveAndFlush(captor.capture());

        User savedUser = captor.getValue();
        assertEquals(1, savedUser.getFailedLoginAttempts());
        assertNull(savedUser.getLockedUntil());
    }

    @Test
    @DisplayName("Không thực hiện gì nếu userId không tồn tại")
    void testRecordFailedAttempt_UserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        loginAttemptService.recordFailedAttempt(999L);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Reset số lần thử sai về 0 và xóa thời gian khóa tài khoản khi đăng nhập thành công")
    void testResetFailedAttempts_Success() {
        sampleUser.setFailedLoginAttempts(5);
        sampleUser.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));

        loginAttemptService.resetFailedAttempts(1L);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).saveAndFlush(captor.capture());

        User savedUser = captor.getValue();
        assertEquals(0, savedUser.getFailedLoginAttempts());
        assertNull(savedUser.getLockedUntil());
    }
}
