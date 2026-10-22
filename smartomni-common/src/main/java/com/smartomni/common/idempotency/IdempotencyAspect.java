package com.smartomni.common.idempotency;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

/**
 * Aspect xử lý Idempotency bằng cách kiểm tra và lưu header X-Idempotency-Key vào Redis.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    private final StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(idempotent)")
    public Object handleIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();
        String idempotencyKey = request.getHeader(AppConstants.HEADER_IDEMPOTENCY_KEY);

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            if (idempotent.required()) {
                throw new BusinessException(
                        "Header " + AppConstants.HEADER_IDEMPOTENCY_KEY + " là bắt buộc cho thao tác này",
                        HttpStatus.BAD_REQUEST,
                        "IDEMPOTENCY_KEY_REQUIRED"
                );
            }
            return joinPoint.proceed();
        }

        idempotencyKey = idempotencyKey.trim();
        String redisKey = idempotent.keyPrefix() + idempotencyKey;

        if (stringRedisTemplate != null) {
            Boolean success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "PROCESSING", idempotent.expireInSeconds(), TimeUnit.SECONDS);

            if (Boolean.FALSE.equals(success)) {
                log.warn("Phát hiện request trùng lặp với Idempotency Key: {}", idempotencyKey);
                throw new BusinessException(
                        "Yêu cầu đang được xử lý hoặc đã được thực hiện trước đó (Idempotency conflict)",
                        HttpStatus.CONFLICT,
                        "DUPLICATE_REQUEST"
                );
            }
        }

        try {
            Object result = joinPoint.proceed();
            if (stringRedisTemplate != null) {
                stringRedisTemplate.opsForValue().set(redisKey, "COMPLETED", idempotent.expireInSeconds(), TimeUnit.SECONDS);
            }
            return result;
        } catch (Throwable t) {
            if (stringRedisTemplate != null) {
                stringRedisTemplate.delete(redisKey);
            }
            throw t;
        }
    }
}
