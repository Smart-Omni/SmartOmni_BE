package com.smartomni.tenant.util;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SepayVietQrHelper {

    private static final String VIETQR_BASE_URL = "https://vietqr.app/img";

    private SepayVietQrHelper() {
    }

    public static String generateOrderCode(Long tenantId, Long paymentId) {
        return String.format("PRO%04d%05d", tenantId != null ? tenantId % 10000 : 0, paymentId != null ? paymentId % 100000 : 0);
    }

    public static String buildQrUrl(String accountNumber, String bankId, BigDecimal amount, String memo, String template) {
        StringBuilder sb = new StringBuilder(VIETQR_BASE_URL);
        sb.append("?acc=").append(accountNumber != null ? accountNumber.trim() : "");
        sb.append("&bank=").append(bankId != null ? bankId.trim() : "");
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("&amount=").append(amount.longValue());
        }
        if (memo != null && !memo.isBlank()) {
            sb.append("&des=").append(URLEncoder.encode(memo.trim(), StandardCharsets.UTF_8));
        }
        if (template != null && !template.isBlank()) {
            sb.append("&template=").append(template.trim());
        }
        return sb.toString();
    }
}
