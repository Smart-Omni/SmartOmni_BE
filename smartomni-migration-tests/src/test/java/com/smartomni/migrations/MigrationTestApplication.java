package com.smartomni.migrations;

import com.smartomni.auth.repository.TenantLookupRepository;
import com.smartomni.auth.service.EmailService;
import com.smartomni.auth.service.impl.AuthServiceImpl;
import com.smartomni.auth.service.impl.UserLoginAttemptServiceImpl;
import com.smartomni.common.persistence.RlsDatabaseConfig;
import com.smartomni.common.security.JwtTokenProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Boot's real Flyway/JPA startup path with entities from all seven services. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = {
        "com.smartomni.auth.entity", "com.smartomni.tenant.entity", "com.smartomni.catalog.entity",
        "com.smartomni.inventory.entity", "com.smartomni.order.entity",
        "com.smartomni.integration.entity", "com.smartomni.ai.entity"
})
@EnableJpaRepositories(basePackages = "com.smartomni.auth.repository")
@Import({RlsDatabaseConfig.class, AuthServiceImpl.class, UserLoginAttemptServiceImpl.class, TenantLookupRepository.class, JwtTokenProvider.class})
public class MigrationTestApplication {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public EmailService emailService() {
        return org.mockito.Mockito.mock(EmailService.class);
    }

    @Bean
    public org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate() {
        org.springframework.data.redis.core.StringRedisTemplate template = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations ops = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.lenient().when(template.opsForValue()).thenReturn(ops);
        return template;
    }
}
