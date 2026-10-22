package com.smartomni.auth.config;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.security.JwtAuthenticationFilter;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.security.TokenBlacklistValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // FR-006: hash mật khẩu chuẩn BCrypt
    }

    /**
     * Bean kiểm tra Redis Blacklist: nếu key "auth:blacklist:<token>" tồn tại trong Redis
     * thì token đã bị thu hồi (sau khi Logout) và sẽ không được xác thực.
     */
    @Bean
    public TokenBlacklistValidator tokenBlacklistValidator(StringRedisTemplate stringRedisTemplate) {
        return token -> {
            boolean blacklisted = Boolean.TRUE.equals(
                    stringRedisTemplate.hasKey(AppConstants.REDIS_PREFIX_BLACKLIST + token));
            if (blacklisted) {
                log.debug("Token is blacklisted in Redis, rejecting authentication");
            }
            return blacklisted;
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtTokenProvider jwtTokenProvider,
                                           TokenBlacklistValidator tokenBlacklistValidator,
                                           StringRedisTemplate stringRedisTemplate) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(
                    new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/api/auth/**", "/actuator/**").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
