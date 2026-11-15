package io.github.saumya.portfolio.domain;

import java.util.List;

/**
 * First page of an Articles API search.
 *
 * @param hitCount    total number of matching articles reported by the Articles API
 * @param articles    articles in Articles API order
 * @param fromFixture true when the result came from the saved Articles API data, not a live call
 */
public record ArticleSearchResult(long hitCount, List<Article> articles, boolean fromFixture) {

    /** A live result. */
    public ArticleSearchResult(long hitCount, List<Article> articles) {
        this(hitCount, articles, false);
    }
}
