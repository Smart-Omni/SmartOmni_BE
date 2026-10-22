package com.smartomni.common.security;

import com.smartomni.common.constant.AppConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceSecurityAutoConfigurationTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectProvider<StringRedisTemplate> redisProvider;

    private final ServiceSecurityAutoConfiguration autoConfiguration = new ServiceSecurityAutoConfiguration();

    @Test
    @DisplayName("TokenBlacklistValidator trả về true khi token nằm trong Redis blacklist")
    void testTokenBlacklistValidator_TokenIsBlacklisted() {
        String token = "sample-revoked-token";
        when(redisProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token)).thenReturn(true);

        TokenBlacklistValidator validator = autoConfiguration.tokenBlacklistValidator(redisProvider);
        assertTrue(validator.isBlacklisted(token));

        verify(stringRedisTemplate, times(1)).hasKey("auth:blacklist:" + token);
    }

    @Test
    @DisplayName("TokenBlacklistValidator trả về false khi token không nằm trong Redis blacklist")
    void testTokenBlacklistValidator_TokenNotBlacklisted() {
        String token = "sample-active-token";
        when(redisProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token)).thenReturn(false);

        TokenBlacklistValidator validator = autoConfiguration.tokenBlacklistValidator(redisProvider);
        assertFalse(validator.isBlacklisted(token));

        verify(stringRedisTemplate, times(1)).hasKey("auth:blacklist:" + token);
    }

    @Test
    @DisplayName("TokenBlacklistValidator trả về false một cách an toàn khi StringRedisTemplate không có sẵn")
    void testTokenBlacklistValidator_RedisTemplateNotAvailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        TokenBlacklistValidator validator = autoConfiguration.tokenBlacklistValidator(redisProvider);
        assertFalse(validator.isBlacklisted("any-token"));
    }
}
