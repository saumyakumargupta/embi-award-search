package io.github.saumya.portfolio.client;

import io.github.saumya.portfolio.domain.ArticleSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tries the live Articles API first and falls back to saved data only when the live call fails.
 *
 * <p>A successful live answer is always used, even if it is empty: "no publications" from the live API
 * is real information. Only a failure (timeout, HTTP error, unreadable body, unreachable) triggers the
 * fallback. If the saved data has no entry for the grant either, the original live failure is reported,
 * with a note that no saved data was available.
 */
public class FallbackArticlesClient implements ArticlesClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackArticlesClient.class);

    private final ArticlesClient live;
    private final ArticlesClient saved;

    public FallbackArticlesClient(ArticlesClient live, ArticlesClient saved) {
        this.live = live;
        this.saved = saved;
    }

    @Override
    public ArticleSearchResult search(String query, int pageSize) {
        try {
            return live.search(query, pageSize);
        } catch (UpstreamException liveFailure) {
            try {
                ArticleSearchResult result = saved.search(query, pageSize);
                log.info("Live Articles API failed for {} ({}); using saved data", query, liveFailure.getMessage());
                return result;
            } catch (UpstreamException noSavedData) {
                throw new UpstreamException(liveFailure.service(), liveFailure.kind(), liveFailure.upstreamStatus(),
                        liveFailure.getMessage() + "; " + noSavedData.getMessage(), liveFailure);
            }
        }
    }
}
