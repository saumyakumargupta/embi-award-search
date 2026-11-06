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
     * @param mode {@code live} (default) calls the Grants API; {@code demo} uses the bundled fixture
     *             in {@code src/main/resources/demo/} while still calling the live Articles API
     */
    public record Grist(String baseUrl, Duration readTimeout, Mode mode) {
        public Grist {
            if (mode == null) {
                mode = Mode.LIVE;
            }
        }
    }

    public enum Mode { LIVE, DEMO }

    /**
     * @param maxConcurrent  Articles API calls allowed in flight at once, across all requests
     * @param lookupDeadline overall wait for all of one request's article lookups (queueing included)
     */
    public record Articles(String baseUrl, Duration readTimeout, int maxConcurrent, Duration lookupDeadline) {
    }
}
