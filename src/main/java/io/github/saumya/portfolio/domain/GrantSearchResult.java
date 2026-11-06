package io.github.saumya.portfolio.domain;

import java.util.List;

/**
 * One page of Grist results.
 *
 * @param hitCount total number of matching grants reported by Grist (null if not reported)
 * @param grants   grants in Grist order
 */
public record GrantSearchResult(Long hitCount, List<Grant> grants) {
}
