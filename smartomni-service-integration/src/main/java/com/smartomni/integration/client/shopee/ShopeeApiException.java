package com.smartomni.integration.client.shopee;

/**
 * A Shopee call failed. The message never contains the request URL or secret credentials.
 */
public class ShopeeApiException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;
    private final String requestId;
    private final boolean transientFailure;

    public ShopeeApiException(String message, int httpStatus, String errorCode, String requestId, boolean transientFailure) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.requestId = requestId;
        this.transientFailure = transientFailure;
    }

    public static ShopeeApiException transport(String message) {
        return new ShopeeApiException(message, 0, null, null, true);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }

    /** Network failure, timeout or HTTP 5xx: the request may be retried later. */
    public boolean isTransientFailure() {
        return transientFailure;
    }

    /** The platform answered and refused (error code returned in envelope). */
    public boolean isRejection() {
        return !transientFailure;
    }

    /** Short, secret-free description for logs. */
    public String summary() {
        return "httpStatus=" + httpStatus + " error=" + errorCode + " requestId=" + requestId
                + " transient=" + transientFailure;
    }
}
