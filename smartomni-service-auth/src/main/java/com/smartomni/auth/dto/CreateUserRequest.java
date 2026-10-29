package com.smartomni.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO yêu cầu tạo tài khoản Staff/Manager bởi Admin hoặc Tenant Owner.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    /**
     * Mật khẩu khởi tạo; nếu không cung cấp, sẽ được sinh ngẫu nhiên.
     */
    @Size(min = 6, max = 100, message = "Mật khẩu phải từ 6 đến 100 ký tự")
    @JsonAlias({"password"})
    private String password;

    @NotBlank(message = "Họ và tên không được để trống")
    @Size(max = 100, message = "Họ và tên không được vượt quá 100 ký tự")
    @JsonAlias({"fullName", "full_name"})
    @JsonProperty("full_name")
    private String fullName;

    @Size(max = 50, message = "Số điện thoại không được vượt quá 50 ký tự")
    private String phone;

    @Size(max = 500, message = "Đường dẫn ảnh đại diện không được vượt quá 500 ký tự")
    private String avatar;

    /**
     * Role của tài khoản mới. Bắt buộc phải là một trong: ADMIN, TENANT_ADMIN, MANAGER, CUSTOMER.
     * SUPER_ADMIN không được phép tạo thông qua API này.
     */
    @NotNull(message = "Role không được để trống")
    private String role;
}

