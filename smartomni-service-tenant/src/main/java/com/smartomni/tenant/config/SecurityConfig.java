package com.smartomni.tenant.config;

import com.smartomni.common.security.JwtAuthenticationFilter;
import com.smartomni.common.security.JsonAuthenticationEntryPoint;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.security.PublicEndpointPaths;
import com.smartomni.common.security.TokenBlacklistValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtTokenProvider jwtTokenProvider,
                                           TokenBlacklistValidator tokenBlacklistValidator,
                                           StringRedisTemplate stringRedisTemplate) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
            .addFilterBefore(
                    new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator, stringRedisTemplate),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(PublicEndpointPaths.PUBLIC_AUTH_ONBOARDING_ERROR).permitAll()
                .requestMatchers(
                        "/api/v1/tenants/check-subdomain",
                        "/api/v1/tenants/public/**",
                        "/webhook/sepay",
                        "/api/v1/payments/sepay/**",
                        "/actuator/**"
                ).permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
