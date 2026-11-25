package com.smartomni.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO yêu cầu cập nhật thông tin tài khoản bởi Admin/Tenant Owner.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateUserRequest {

    @Size(max = 100, message = "Họ và tên không được vượt quá 100 ký tự")
    @JsonAlias({"fullName", "full_name"})
    @JsonProperty("full_name")
    private String fullName;

    @Size(max = 50, message = "Số điện thoại không được vượt quá 50 ký tự")
    private String phone;

    @Size(max = 500, message = "Đường dẫn ảnh đại diện không được vượt quá 500 ký tự")
    private String avatar;

    /**
     * Thay đổi role (chỉ SUPER_ADMIN có thể thay đổi sang ADMIN; ADMIN có thể thay đổi từ MANAGER trở xuống).
     */
    private String role;
}

