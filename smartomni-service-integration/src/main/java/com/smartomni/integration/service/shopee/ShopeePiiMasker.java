package com.smartomni.integration.service.shopee;

/**
 * Masks buyer phone numbers and street addresses per Shopee Open Platform privacy policy.
 */
public final class ShopeePiiMasker {

    private ShopeePiiMasker() {
    }

    /**
     * Masks the middle of a phone number with {@code ****}, preserving values already masked.
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String trimmed = phone.trim();
        if (trimmed.contains("*")) {
            return trimmed;
        }
        if (trimmed.length() <= 4) {
            return "****";
        }
        if (trimmed.length() <= 7) {
            return trimmed.substring(0, 2) + "****" + trimmed.substring(trimmed.length() - 2);
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 3);
    }

    /**
     * Masks the street-level segment of a full address while keeping district/city context when comma-separated.
     */
    public static String maskAddress(String fullAddress) {
        if (fullAddress == null || fullAddress.isBlank()) {
            return fullAddress;
        }
        String trimmed = fullAddress.trim();
        if (trimmed.contains("*")) {
            return trimmed;
        }
        int firstComma = trimmed.indexOf(',');
        if (firstComma > 0 && firstComma < trimmed.length() - 1) {
            return "******" + trimmed.substring(firstComma);
        }
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 2) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
