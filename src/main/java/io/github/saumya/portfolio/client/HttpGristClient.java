package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Grist client over HTTP.
 *
 * <p>Grist puts the query in the URL path rather than in a query string:
 * {@code .../GristAPI/rest/get/query=<query>&resultType=core&format=json}. The query is therefore
 * percent-encoded by us (spaces as {@code %20}, quotes as {@code %22}, {@code &} as {@code %26},
 * and so on) and the URI is passed to the HTTP client pre-built, so it is not encoded twice.
 */
public class HttpGristClient implements GristClient {

    static final String SERVICE = "Europe PMC Grants API";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public HttpGristClient(RestClient restClient, ObjectMapper mapper, String baseUrl) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public GrantSearchResult search(String gristQuery) {
        return UpstreamCall.getJson(restClient, mapper, SERVICE, buildUri(gristQuery), GristResponseParser::parse);
    }

    URI buildUri(String gristQuery) {
        return URI.create(baseUrl + "/rest/get/query=" + encodePathValue(gristQuery)
                + "&resultType=core&format=json&page=1");
    }

    /**
     * Percent-encodes everything except unreserved characters and the field separator {@code :}, which
     * Grist's documented examples use literally.
     */
    static String encodePathValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%3A", ":");
    }
}
