package io.github.saumya.portfolio.client;

/**
 * A failure talking to a Europe PMC API. Carries enough detail for the service to report which
 * lookup failed and why, without leaking stack traces to API clients.
 */
public class UpstreamException extends RuntimeException {

    public enum Kind {
        /** The request did not complete within the configured timeout. */
        TIMEOUT,
        /** The upstream service answered with a non-2xx HTTP status. */
        HTTP_ERROR,
        /** The upstream answered 2xx but the body could not be understood. */
        BAD_RESPONSE,
        /** Connection refused, reset, DNS failure and similar. */
        UNAVAILABLE
    }

    private final String service;
    private final Kind kind;
    private final Integer upstreamStatus;

    public UpstreamException(String service, Kind kind, Integer upstreamStatus, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
    }

    public String service() {
        return service;
    }

    public Kind kind() {
        return kind;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }
}
