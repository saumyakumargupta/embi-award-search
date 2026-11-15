package io.github.saumya.portfolio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables, bound from {@code portfolio.*} in application.yml or environment variables
 * (e.g. {@code PORTFOLIO_ARTICLES_MAX_CONCURRENT=2}).
 */
@ConfigurationProperties(prefix = "portfolio")
public record PortfolioProperties(Grist grist, Articles articles, Duration connectTimeout) {

    /**
     * @param mode     {@code live} (default) calls the Grants API; {@code demo} uses the bundled fixture
     *                 in {@code src/main/resources/demo/} while still calling the live Articles API
     * @param maxPages most Grist pages (25 records each) read to collect enough distinct grants
     */
    public record Grist(String baseUrl, Duration readTimeout, Mode mode, int maxPages) {
        public Grist {
            if (mode == null) {
                mode = Mode.LIVE;
            }
            if (maxPages < 1) {
                maxPages = 3;
            }
        }
    }

    public enum Mode { LIVE, DEMO }

    /**
     * @param maxConcurrent  Articles API calls allowed in flight at once, across all requests
     * @param lookupDeadline overall wait for all of one request's article lookups (queueing included)
     * @param mode           {@code fallback} (default): live, but use saved data for a grant if the live call
     *                       fails; {@code live}: never use saved data; {@code demo}: always use saved data
     */
    public record Articles(String baseUrl, Duration readTimeout, int maxConcurrent, Duration lookupDeadline,
                           ArticlesMode mode) {
        public Articles {
            if (mode == null) {
                mode = ArticlesMode.FALLBACK;
            }
        }
    }

    public enum ArticlesMode { LIVE, FALLBACK, DEMO }
}
