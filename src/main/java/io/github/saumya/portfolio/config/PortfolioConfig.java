package io.github.saumya.portfolio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.client.ArticlesClient;
import io.github.saumya.portfolio.client.DemoGristClient;
import io.github.saumya.portfolio.client.FallbackArticlesClient;
import io.github.saumya.portfolio.client.FixtureArticlesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.saumya.portfolio.client.GristClient;
import io.github.saumya.portfolio.client.HttpArticlesClient;
import io.github.saumya.portfolio.client.HttpGristClient;
import io.github.saumya.portfolio.service.PortfolioService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(PortfolioProperties.class)
public class PortfolioConfig {

    private static final Logger log = LoggerFactory.getLogger(PortfolioConfig.class);

    private static final String USER_AGENT = "institution-portfolio/0.1 (take-home exercise)";

    @Bean
    HttpClient upstreamHttpClient(PortfolioProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                // Honour -Dhttps.proxyHost etc.; the JDK client ignores them unless told to.
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                // HTTP/1.1 keeps behaviour predictable through proxies; we make few, small requests.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    GristClient gristClient(HttpClient httpClient, ObjectMapper mapper, PortfolioProperties props) {
        if (props.grist().mode() == PortfolioProperties.Mode.DEMO) {
            log.warn("DEMO MODE: grants come from the bundled fixture {}, not the live Europe PMC Grants API. "
                    + "Publication lookups still use the live Articles API.", DemoGristClient.DEFAULT_FIXTURE);
            return new DemoGristClient(mapper, DemoGristClient.DEFAULT_FIXTURE);
        }
        return new HttpGristClient(restClient(httpClient, props.grist().readTimeout()), mapper, props.grist().baseUrl());
    }

    @Bean
    ArticlesClient articlesClient(HttpClient httpClient, ObjectMapper mapper, PortfolioProperties props) {
        ArticlesClient live = new HttpArticlesClient(restClient(httpClient, props.articles().readTimeout()), mapper,
                props.articles().baseUrl());
        return switch (props.articles().mode()) {
            case LIVE -> live;
            case DEMO -> {
                FixtureArticlesClient saved = new FixtureArticlesClient(mapper, FixtureArticlesClient.DEFAULT_FIXTURE);
                log.warn("ARTICLES DEMO MODE: publications come from saved data captured on {} ({} grants), "
                        + "not the live Articles API.", saved.capturedOn(), saved.size());
                yield saved;
            }
            case FALLBACK -> {
                FixtureArticlesClient saved = new FixtureArticlesClient(mapper, FixtureArticlesClient.DEFAULT_FIXTURE);
                log.info("Articles fallback enabled: if the live Articles API fails, saved data captured on {} "
                        + "({} grants) is used for those grants.", saved.capturedOn(), saved.size());
                yield new FallbackArticlesClient(live, saved);
            }
        };
    }

    @Bean(destroyMethod = "close")
    ExecutorService articleLookupExecutor() {
        // Cheap threads for blocking I/O; the service's semaphore is what limits load on Europe PMC.
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    PortfolioService portfolioService(GristClient grist, ArticlesClient articles,
                                      ExecutorService articleLookupExecutor, PortfolioProperties props) {
        return new PortfolioService(grist, articles, articleLookupExecutor,
                props.articles().maxConcurrent(), props.articles().lookupDeadline(), props.grist().maxPages());
    }

    private static RestClient restClient(HttpClient httpClient, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }
}
