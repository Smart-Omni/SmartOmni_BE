package com.smartomni.auth.service;

import com.smartomni.auth.dto.AdminUpdateUserRequest;
import com.smartomni.auth.dto.AssignRolesRequest;
import com.smartomni.auth.dto.CreateUserRequest;
import com.smartomni.auth.dto.PageResponse;
import com.smartomni.auth.dto.UpdateProfileRequest;
import com.smartomni.auth.dto.UpdateUserStatusRequest;
import com.smartomni.auth.dto.UserProfileResponse;

public interface UserService {

    /**
     * Lấy thông tin cá nhân của người dùng hiện tại từ userId.
     *
     * @param userId ID người dùng
     * @return UserProfileResponse
     */
    UserProfileResponse getCurrentUserProfile(Long userId);

    /**
     * Cập nhật thông tin cá nhân của người dùng hiện tại (fullName, phone, avatar).
     *
     * @param userId  ID người dùng
     * @param request dữ liệu cập nhật
     * @return UserProfileResponse sau khi cập nhật
     */
    UserProfileResponse updateCurrentUserProfile(Long userId, UpdateProfileRequest request);

    // ======================== ADMIN APIs ========================

    /**
     * Tạo tài khoản Staff/Manager mới bởi Admin/Tenant Owner.
     * Kiểm tra email trùng lặp, hash password, gán role và liên kết với tenantId của người tạo.
     *
     * @param callerUserId  ID người dùng đang thực hiện tạo tài khoản (để xác định tenantId)
     * @param callerRole    Role của người đang gọi API
     * @param request       Thông tin tài khoản cần tạo
     * @return UserProfileResponse của tài khoản vừa tạo
     */
    UserProfileResponse createUser(Long callerUserId, String callerRole, CreateUserRequest request);

    /**
     * Lấy danh sách và tìm kiếm tài khoản trong Tenant hiện tại (hoặc toàn bộ nếu SUPER_ADMIN).
     * Hỗ trợ phân trang, sắp xếp, lọc theo status/role, tìm kiếm keyword.
     *
     * @param callerUserId ID người dùng đang gọi (để xác định tenantId)
     * @param callerRole   Role của người đang gọi
     * @param keyword      Từ khóa tìm kiếm (email, fullName, phone)
     * @param status       Lọc theo trạng thái (ACTIVE, DISABLED)
     * @param role         Lọc theo role
     * @param page         Số trang (0-indexed)
     * @param size         Kích thước trang
     * @param sortBy       Trường sắp xếp (mặc định: createdAt)
     * @param sortDir      Chiều sắp xếp (asc/desc)
     * @return PageResponse<UserProfileResponse>
     */
    PageResponse<UserProfileResponse> listUsers(
            Long callerUserId, String callerRole,
            String keyword, String status, String role,
            int page, int size, String sortBy, String sortDir
    );

    /**
     * Cập nhật thông tin tài khoản bởi Admin/Tenant Owner.
     *
     * @param callerUserId  ID người dùng đang thực hiện cập nhật
     * @param callerRole    Role của người đang gọi
     * @param targetUserId  ID tài khoản cần cập nhật
     * @param request       Thông tin mới cần cập nhật
     * @return UserProfileResponse sau khi cập nhật
     */
    UserProfileResponse adminUpdateUser(Long callerUserId, String callerRole, Long targetUserId, AdminUpdateUserRequest request);

    /**
     * Thay đổi trạng thái tài khoản (ACTIVE / DISABLED).
     * Khi DISABLED, tự động thu hồi Refresh Token của user đó trong Redis.
     *
     * @param callerUserId  ID người dùng đang thực hiện thay đổi
     * @param callerRole    Role của người đang gọi
     * @param targetUserId  ID tài khoản cần thay đổi trạng thái
     * @param request       Trạng thái mới
     * @return UserProfileResponse sau khi cập nhật
     */
    UserProfileResponse updateUserStatus(Long callerUserId, String callerRole, Long targetUserId, UpdateUserStatusRequest request);

    /**
     * Xóa mềm tài khoản (chuyển status sang DISABLED).
     * Không được xóa chính mình hoặc tài khoản ngoài tenant.
     *
     * @param callerUserId  ID người dùng đang thực hiện xóa
     * @param callerRole    Role của người đang gọi
     * @param targetUserId  ID tài khoản cần xóa
     */
    /**
     * Gán hoặc thay đổi danh sách Role cho người dùng (Admin/Tenant Owner).
     *
     * @param callerUserId  ID người dùng thực hiện thao tác
     * @param callerRole    Role của người thực hiện
     * @param targetUserId  ID người dùng được gán role
     * @param request       DTO chứa danh sách role mới
     * @return UserProfileResponse sau khi cập nhật
     */
    UserProfileResponse assignRoles(Long callerUserId, String callerRole, Long targetUserId, AssignRolesRequest request);

}
