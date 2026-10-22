package com.smartomni.common.security;

/**
 * Interface kiểm tra token có nằm trong danh sách đen (Blacklist) hay không (ví dụ kiểm tra qua Redis sau khi Logout).
 */
@FunctionalInterface
public interface TokenBlacklistValidator {

    /**
     * Kiểm tra xem JWT token có bị thu hồi/nằm trong blacklist không.
     *
     * @param token JWT raw token
     * @return true nếu token đã bị thu hồi/nằm trong blacklist
     */
    boolean isBlacklisted(String token);
}
