package com.smartomni.auth.service;

public interface UserLoginAttemptService {

    /**
     * Ghi nhận một lần đăng nhập sai của người dùng.
     * Sử dụng Transaction Propagation.REQUIRES_NEW để đảm bảo số lần thử sai
     * và thời gian khóa tài khoản được COMMIT vào Database độc lập kể cả khi luồng login() bị rollback.
     *
     * @param userId ID của người dùng đăng nhập sai
     */
    void recordFailedAttempt(Long userId);

    /**
     * Đặt lại số lần thử sai về 0 và xóa thời gian khóa khi đăng nhập thành công.
     *
     * @param userId ID của người dùng đăng nhập thành công
     */
    void resetFailedAttempts(Long userId);
}
