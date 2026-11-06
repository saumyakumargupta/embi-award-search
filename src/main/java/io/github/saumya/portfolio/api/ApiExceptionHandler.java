package io.github.saumya.portfolio.api;

import io.github.saumya.portfolio.client.UpstreamException;
import io.github.saumya.portfolio.service.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps failures to RFC 9457 problem+json responses:
 * <ul>
 *   <li>400 for missing, malformed or out-of-range parameters;</li>
 *   <li>504 when the Grants API times out, 502 for any other Grants API failure.</li>
 * </ul>
 * Article lookup failures never reach here: they are reported per grant inside a 200 response.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException e) {
        return badRequest(e.parameter(), e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missing(MissingServletRequestParameterException e) {
        return badRequest(e.getParameterName(), e.getParameterName() + " is required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail mismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(e.getName(), e.getName() + " must be a whole number");
    }

    @ExceptionHandler(UpstreamException.class)
    ProblemDetail upstream(UpstreamException e) {
        log.warn("Upstream failure: {}", e.getMessage(), e.getCause());
        HttpStatus status = e.kind() == UpstreamException.Kind.TIMEOUT
                ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle("Upstream service failure");
        problem.setProperty("upstreamService", e.service());
        problem.setProperty("failure", e.kind());
        if (e.upstreamStatus() != null) {
            problem.setProperty("upstreamStatus", e.upstreamStatus());
        }
        return problem;
    }

    private static ProblemDetail badRequest(String parameter, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid request");
        problem.setProperty("parameter", parameter);
        return problem;
    }
}
