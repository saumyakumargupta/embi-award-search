package io.github.saumya.portfolio.client;

import io.github.saumya.portfolio.domain.ArticleSearchResult;

/** Searches the Europe PMC Articles REST API. */
public interface ArticlesClient {

    /**
     * Runs an Articles API query and returns the first page in Articles API order.
     *
     * @param query    a complete Articles API query, e.g. {@code GRANT_ID:"098051"}
     * @param pageSize maximum number of articles to return
     * @throws UpstreamException if the Articles API cannot be reached or answers unexpectedly
     */
    ArticleSearchResult search(String query, int pageSize);
}
