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
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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

    /** TikTok Shop webhook receiver (integration service). Authenticated by HMAC signature in the controller. */
    public static final String TIKTOK_WEBHOOK_PATH = "/api/v1/integrations/tiktok/webhooks";

    /** Public storefront catalog (catalog service). Read-only; the tenant comes from the path, never from a JWT. */
    public static final String PUBLIC_STOREFRONT_PRODUCTS_PATH = "/api/v1/products/public/**";

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
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplateProvider.getIfAvailable()), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PublicEndpointPaths.PUBLIC_AUTH_ONBOARDING_ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/webhook/**", "/internal/jobs/**").permitAll()
                        // TikTok webhooks carry an HMAC signature (verified by the controller), never a JWT.
                        .requestMatchers(HttpMethod.POST, TIKTOK_WEBHOOK_PATH).permitAll()
                        // Shoppers browse a store's catalog without an account.
                        .requestMatchers(HttpMethod.GET, PUBLIC_STOREFRONT_PRODUCTS_PATH).permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
