package com.smartomni.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation đánh dấu method API cần bảo vệ Idempotency chống Replay Attack / Double-Submit
 * bằng cách sử dụng Header `X-Idempotency-Key` và Redis Distributed Lock.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * Thời gian tồn tại của key trong Redis (giây). Mặc định: 120s (2 phút).
     */
    long expireInSeconds() default 120;

    /**
     * Tiền tố khóa trong Redis. Mặc định: "idempotency:".
     */
    String keyPrefix() default "idempotency:";

    /**
     * Có bắt buộc phải có header X-Idempotency-Key hay không.
     * Mặc định: true (nếu thiếu sẽ trả về lỗi HTTP 400).
     */
    boolean required() default true;
}
