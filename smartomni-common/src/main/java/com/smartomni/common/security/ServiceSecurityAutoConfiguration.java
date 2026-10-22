package com.smartomni.common.security;

import com.smartomni.common.constant.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** JWT security for services that do not declare their own filter chain. */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(HttpSecurity.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class ServiceSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TokenBlacklistValidator.class)
    public TokenBlacklistValidator tokenBlacklistValidator(ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        return token -> {
            StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
            if (stringRedisTemplate == null) {
                return false;
            }
            try {
                boolean blacklisted = Boolean.TRUE.equals(
                        stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token));
                if (blacklisted) {
                    log.debug("Token is blacklisted in Redis, rejecting authentication");
                }
                return blacklisted;
            } catch (Exception e) {
                log.warn("Failed to check token blacklist in Redis: {}", e.getMessage());
                return false;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain serviceSecurityFilterChain(HttpSecurity http,
                                                          JwtTokenProvider jwtTokenProvider,
                                                          TokenBlacklistValidator tokenBlacklistValidator,
                                                          ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplateProvider.getIfAvailable()), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/webhook/**", "/internal/jobs/**").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
