package io.github.saumya.portfolio.service;

/** Input that the service cannot sensibly turn into an upstream query. Mapped to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    private final String parameter;

    public InvalidRequestException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
