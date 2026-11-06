package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Articles REST API client over HTTP. Uses {@code resultType=core} because only the core result type
 * includes each article's {@code grantsList}, which we need to check the funder of a GRANT_ID match.
 */
public class HttpArticlesClient implements ArticlesClient {

    static final String SERVICE = "Europe PMC Articles API";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public HttpArticlesClient(RestClient restClient, ObjectMapper mapper, String baseUrl) {
        this.restClient = restClient;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public ArticleSearchResult search(String query, int pageSize) {
        return UpstreamCall.getJson(restClient, mapper, SERVICE, buildUri(query, pageSize),
                ArticlesResponseParser::parse);
    }

    URI buildUri(String query, int pageSize) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path("/search")
                .queryParam("query", "{query}")
                .queryParam("format", "json")
                .queryParam("resultType", "core")
                .queryParam("pageSize", pageSize)
                .encode() // template variables are strictly encoded, so '+', '&', '"' are all escaped
                .buildAndExpand(query)
                .toUri();
    }
}
