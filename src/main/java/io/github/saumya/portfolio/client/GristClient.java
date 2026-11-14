package io.github.saumya.portfolio.client;

import io.github.saumya.portfolio.domain.GrantSearchResult;

/** Searches the Europe PMC Grants (Grist) API. */
public interface GristClient {

    /** Records per Grist results page, fixed by the Grants API. */
    int PAGE_SIZE = 25;

    /**
     * Runs a Grist query and returns one results page (up to 25 records) in Grist order.
     * Grist returns one record per person on a grant, so a page can hold fewer than 25 distinct grants.
     *
     * @param gristQuery a complete Grist query, e.g. {@code aff:"University of Manchester"}
     * @param page       1-based page number
     * @throws UpstreamException if Grist cannot be reached or answers unexpectedly
     */
    GrantSearchResult search(String gristQuery, int page);

    /** True when grants come from the bundled demo fixture rather than the live Grants API. */
    default boolean isDemo() {
        return false;
    }
}
