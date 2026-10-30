package com.smartomni.tenant.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for Tenant Onboarding (UC-17, TEN-01.1).
 * Supports both TENANT_ADMIN (new tenant + admin) and STAFF (join existing tenant) flows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantOnboardingRequest {

    @JsonAlias({"name", "businessName", "company_name", "business_name"})
    private String companyName;

    @JsonAlias({"companyName", "company_name", "name"})
    private String businessName;

    @JsonAlias({"companyName", "company_name", "businessName", "business_name"})
    private String name;

    @NotBlank(message = "Subdomain không được để trống")
    @Pattern(regexp = "^[a-z0-9-]{3,30}$", message = "Subdomain chỉ gồm chữ thường, số và dấu gạch ngang, 3-30 ký tự")
    private String subdomain;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    @JsonAlias({"email", "contactEmail", "admin_email", "contact_email"})
    @JsonProperty("admin_email")
    private String adminEmail;

    @JsonAlias({"adminFullName", "admin_full_name", "full_name"})
    @JsonProperty("admin_full_name")
    private String adminFullName;

    @JsonAlias({"adminFullName", "admin_full_name", "fullName"})
    @JsonProperty("full_name")
    private String fullName;

    @JsonAlias({"adminPassword", "admin_password"})
    @JsonProperty("admin_password")
    private String adminPassword;

    @JsonAlias({"adminPassword", "admin_password"})
    private String password;

    @Email(message = "Email liên hệ không đúng định dạng")
    @JsonAlias({"adminEmail", "admin_email", "email"})
    @JsonProperty("contact_email")
    private String contactEmail;

    @JsonAlias({"contactPhone", "contact_phone"})
    @JsonProperty("contact_phone")
    private String contactPhone;

    @JsonAlias({"contactPhone", "contact_phone"})
    private String phone;

    @Builder.Default
    @JsonAlias({"role", "role_choice"})
    @JsonProperty("role_choice")
    private String roleChoice = "TENANT_ADMIN";

    @Builder.Default
    @JsonProperty("plan_type")
    private String planType = "FREE";
    private String initialPlanName;

    public String getName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (companyName != null && !companyName.isBlank()) {
            return companyName;
        }
        return businessName;
    }

    public void setName(String name) {
        this.name = name;
        if (this.companyName == null) {
            this.companyName = name;
        }
        if (this.businessName == null) {
            this.businessName = name;
        }
    }

    public String getCompanyName() {
        return getName();
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
        if (this.name == null) {
            this.name = companyName;
        }
        if (this.businessName == null) {
            this.businessName = companyName;
        }
    }

    public String getBusinessName() {
        return getName();
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
        if (this.name == null) {
            this.name = businessName;
        }
        if (this.companyName == null) {
            this.companyName = businessName;
        }
    }

    public String getEmail() {
        return getAdminEmail();
    }

    public void setEmail(String email) {
        setAdminEmail(email);
    }

    public String getAdminEmail() {
        if (adminEmail != null && !adminEmail.isBlank()) {
            return adminEmail;
        }
        return contactEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
        if (this.contactEmail == null) {
            this.contactEmail = adminEmail;
        }
    }

    public String getContactEmail() {
        if (contactEmail != null && !contactEmail.isBlank()) {
            return contactEmail;
        }
        return adminEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
        if (this.adminEmail == null) {
            this.adminEmail = contactEmail;
        }
    }

    public String getFullName() {
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        return adminFullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
        if (this.adminFullName == null) {
            this.adminFullName = fullName;
        }
    }

    public String getAdminFullName() {
        return getFullName();
    }

    public void setAdminFullName(String adminFullName) {
        this.adminFullName = adminFullName;
        if (this.fullName == null) {
            this.fullName = adminFullName;
        }
    }

    public String getPassword() {
        if (password != null && !password.isBlank()) {
            return password;
        }
        return adminPassword;
    }

    public void setPassword(String password) {
        this.password = password;
        if (this.adminPassword == null) {
            this.adminPassword = password;
        }
    }

    public String getAdminPassword() {
        return getPassword();
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
        if (this.password == null) {
            this.password = adminPassword;
        }
    }

    public String getPhone() {
        if (phone != null && !phone.isBlank()) {
            return phone;
        }
        return contactPhone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
        if (this.contactPhone == null) {
            this.contactPhone = phone;
        }
    }

    public String getContactPhone() {
        return getPhone();
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
        if (this.phone == null) {
            this.phone = contactPhone;
        }
    }

    public String getRoleChoice() {
        if (roleChoice != null && !roleChoice.isBlank()) {
            return roleChoice;
        }
        return "TENANT_ADMIN";
    }

    public void setRoleChoice(String roleChoice) {
        this.roleChoice = roleChoice;
    }

    public String getPlanType() {
        if (planType != null && !planType.isBlank()) {
            return planType;
        }
        return initialPlanName != null ? initialPlanName : "FREE";
    }

    public void setInitialPlanName(String initialPlanName) {
        this.initialPlanName = initialPlanName;
        if (this.planType == null) {
            this.planType = initialPlanName;
        }
    }
}
