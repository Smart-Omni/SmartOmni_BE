package com.smartomni.tenant.config;

import com.smartomni.common.security.JwtAuthenticationFilter;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.security.TokenBlacklistValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                                           TokenBlacklistValidator tokenBlacklistValidator) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(
                    new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistValidator),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(
                        "/api/v1/tenants/check-subdomain",
                        "/api/tenants/check-subdomain",
                        "/api/v1/tenants/onboarding",
                        "/api/tenants/onboarding",
                        "/api/v1/tenants/register/onboarding",
                        "/api/tenants/register/onboarding",
                        "/api/v1/tenants/public/**",
                        "/api/tenants/public/**",
                        "/actuator/**"
                ).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/tenants", "/api/tenants").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
