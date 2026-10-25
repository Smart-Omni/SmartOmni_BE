package com.smartomni.integration.client.tiktok;

/**
 * A TikTok call failed. The message never contains the request URL (token calls carry app_secret and
 * auth_code in the query string).
 */
public class TiktokApiException extends RuntimeException {

    private final int httpStatus;
    private final Integer apiCode;
    private final String requestId;
    private final boolean transientFailure;

    public TiktokApiException(String message, int httpStatus, Integer apiCode, String requestId, boolean transientFailure) {
        super(message);
        this.httpStatus = httpStatus;
        this.apiCode = apiCode;
        this.requestId = requestId;
        this.transientFailure = transientFailure;
    }

    public static TiktokApiException transport(String message) {
        return new TiktokApiException(message, 0, null, null, true);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public Integer getApiCode() {
        return apiCode;
    }

    public String getRequestId() {
        return requestId;
    }

    /** Network failure, timeout or HTTP 5xx: the request may be retried later. Not a rejection. */
    public boolean isTransientFailure() {
        return transientFailure;
    }

    /** The platform answered and refused (HTTP 4xx or envelope code other than 0). */
    public boolean isRejection() {
        return !transientFailure;
    }

    /** Short, secret-free description for logs. */
    public String summary() {
        return "httpStatus=" + httpStatus + " code=" + apiCode + " requestId=" + requestId
                + " transient=" + transientFailure;
    }
}
