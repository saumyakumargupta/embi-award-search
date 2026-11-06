package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.saumya.portfolio.domain.Article;
import io.github.saumya.portfolio.domain.ArticleSearchResult;

import java.util.ArrayList;
import java.util.List;

/** Turns an Articles REST API {@code format=json&resultType=core} search response into {@link Article}s. */
public final class ArticlesResponseParser {

    private ArticlesResponseParser() {
    }

    public static ArticleSearchResult parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Articles response is not a JSON object");
        }
        long hitCount = root.path("hitCount").asLong(0);
        List<Article> articles = new ArrayList<>();
        for (JsonNode result : Json.list(Json.path(root, "resultList", "result"))) {
            articles.add(toArticle(result));
        }
        return new ArticleSearchResult(hitCount, articles);
    }

    private static Article toArticle(JsonNode r) {
        List<Article.FundingStatement> funding = new ArrayList<>();
        for (JsonNode g : Json.list(Json.path(r, "grantsList", "grant"))) {
            funding.add(new Article.FundingStatement(Json.text(g, "agency"), Json.text(g, "grantId")));
        }
        return new Article(
                Json.text(r, "source"),
                Json.text(r, "id"),
                Json.text(r, "pmid"),
                Json.text(r, "pmcid"),
                Json.text(r, "doi"),
                Json.text(r, "title"),
                journal(r),
                Json.text(r, "pubYear"),
                funding);
    }

    /** Journal title for journal articles, publisher/server name for preprints and books. */
    private static String journal(JsonNode r) {
        String journal = Json.text(r, "journalInfo", "journal", "title");
        if (journal == null) {
            journal = Json.text(r, "journalTitle"); // lite result type
        }
        if (journal == null) {
            journal = Json.text(r, "bookOrReportDetails", "publisher"); // e.g. bioRxiv preprints
        }
        return journal;
    }
}
