package io.github.saumya.portfolio.domain;

import java.util.List;

/**
 * A publication returned by the Europe PMC Articles REST API, including the funding statements
 * ({@code grantsList}) that the Articles API reports for it.
 */
public record Article(
        String source,
        String id,
        String pmid,
        String pmcid,
        String doi,
        String title,
        String journal,
        String pubYear,
        List<FundingStatement> fundingStatements) {

    /** One entry of the article's {@code grantsList}. Either field may be null. */
    public record FundingStatement(String agency, String grantId) {
    }
}
