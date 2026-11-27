package com.smartomni.catalog.service;

/**
 * Buyer-facing product page URLs on the marketplaces (the Shopee and TikTok Shop simulators in local/demo setups).
 * Shared by the merchant catalog and the public storefront so both open the same page.
 */
public final class MarketplaceProductUrls {

    /** Shopee simulator buyer page; the stored URL of the retired Render host is rewritten to it. */
    public static final String SHOPEE_SIMULATOR_BASE_URL = "https://shopiz-kappa.vercel.app/product/";
    private static final String LEGACY_SHOPEE_BASE_URL = "https://shopee-simulator.onrender.com/product/";

    private MarketplaceProductUrls() {
    }

    /** Stored URL (legacy host rewritten) or the simulator page of {@code itemId}; null when neither exists. */
    public static String shopee(String storedUrl, String itemId) {
        if (storedUrl != null && !storedUrl.isBlank()) {
            String url = storedUrl.trim();
            return url.startsWith(LEGACY_SHOPEE_BASE_URL)
                    ? SHOPEE_SIMULATOR_BASE_URL + url.substring(LEGACY_SHOPEE_BASE_URL.length())
                    : url;
        }
        return itemId == null || itemId.isBlank() ? null : SHOPEE_SIMULATOR_BASE_URL + itemId.trim();
    }

    /** Stored URL, else {@code template} with {@code {productId}} replaced; null when neither exists. */
    public static String tiktok(String storedUrl, String itemId, String template) {
        if (storedUrl != null && !storedUrl.isBlank()) {
            return storedUrl.trim();
        }
        return tiktokFromTemplate(template, itemId);
    }

    /** {@code template} with {@code {productId}} replaced; null when either is blank, so no link to a wrong site is built. */
    public static String tiktokFromTemplate(String template, String itemId) {
        if (template == null || template.isBlank() || itemId == null || itemId.isBlank()) return null;
        return template.trim().replace("{productId}", itemId.trim());
    }

    /** Only absolute http(s) URLs without credentials are handed to a browser. */
    public static String safeHttpUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
