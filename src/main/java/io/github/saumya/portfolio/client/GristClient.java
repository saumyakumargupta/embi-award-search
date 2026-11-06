package io.github.saumya.portfolio.client;

import io.github.saumya.portfolio.domain.GrantSearchResult;

/** Searches the Europe PMC Grants (Grist) API. */
public interface GristClient {

    /**
     * Runs a Grist query and returns the first results page (up to 25 grants) in Grist order.
     *
     * @param gristQuery a complete Grist query, e.g. {@code aff:"University of Manchester"}
     * @throws UpstreamException if Grist cannot be reached or answers unexpectedly
     */
    GrantSearchResult search(String gristQuery);

    /** True when grants come from the bundled demo fixture rather than the live Grants API. */
    default boolean isDemo() {
        return false;
    }
}
