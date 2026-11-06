package io.github.saumya.portfolio.domain;

import java.util.List;

/**
 * First page of an Articles API search.
 *
 * @param hitCount total number of matching articles reported by the Articles API
 * @param articles articles in Articles API order
 */
public record ArticleSearchResult(long hitCount, List<Article> articles) {
}
