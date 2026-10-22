package com.smartomni.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Sinh va xac thuc JWT dung chung cho toan he thong.
 * Claims chuan: sub (user_id), role, tenantId (null neu SUPER_ADMIN).
 * Xem UC-01 (Dang nhap he thong) / FR-001, FR-002.
 */
@Component
public class JwtTokenProvider {

    @Value("${smartomni.jwt.secret}")
    private String jwtSecret;

    @Value("${smartomni.jwt.expiration-ms:86400000}")
    private long expirationMs;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(Long userId, String role, Long tenantId, String subdomain) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        if (userId != null) {
            claims.put("user_id", userId);
        }
        if (tenantId != null) {
            claims.put("tenant_id", tenantId);
            claims.put("tenantId", tenantId);
        }
        if (subdomain != null) {
            claims.put("subdomain", subdomain);
        }

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateToken(Long userId, String role, Long tenantId) {
        return generateToken(userId, role, tenantId, null);
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        Claims claims = parseClaims(token);
        Object userId = claims.get("user_id");
        if (userId != null) {
            return Long.valueOf(userId.toString());
        }
        return Long.valueOf(claims.getSubject());
    }

    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public Long getTenantId(String token) {
        Claims claims = parseClaims(token);
        Object tenantId = claims.get("tenant_id");
        if (tenantId == null) {
            tenantId = claims.get("tenantId");
        }
        return tenantId == null ? null : Long.valueOf(tenantId.toString());
    }

    public String getSubdomain(String token) {
        return parseClaims(token).get("subdomain", String.class);
    }

    public Date getExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    public long getRemainingExpirationMs(String token) {
        try {
            Date expiration = getExpiration(token);
            if (expiration == null) {
                return 0;
            }
            long remaining = expiration.getTime() - System.currentTimeMillis();
            return Math.max(remaining, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
