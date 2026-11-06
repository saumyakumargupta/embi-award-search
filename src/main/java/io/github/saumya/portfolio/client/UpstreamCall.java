package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.function.Function;

/** Executes a GET against a Europe PMC API and maps every failure to an {@link UpstreamException}. */
final class UpstreamCall {

    private UpstreamCall() {
    }

    static <T> T getJson(RestClient restClient, ObjectMapper mapper, String service, URI uri,
                         Function<JsonNode, T> parser) {
        String body;
        try {
            body = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new UpstreamException(service, UpstreamException.Kind.HTTP_ERROR, e.getStatusCode().value(),
                    service + " returned HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new UpstreamException(service, UpstreamException.Kind.TIMEOUT, null,
                        service + " did not respond in time", e);
            }
            throw new UpstreamException(service, UpstreamException.Kind.UNAVAILABLE, null,
                    service + " could not be reached", e);
        }

        if (body == null || body.isBlank()) {
            throw new UpstreamException(service, UpstreamException.Kind.BAD_RESPONSE, null,
                    service + " returned an empty body", null);
        }
        try {
            return parser.apply(mapper.readTree(body));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new UpstreamException(service, UpstreamException.Kind.BAD_RESPONSE, null,
                    service + " returned a response that could not be read", e);
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
