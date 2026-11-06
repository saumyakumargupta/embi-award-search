package io.github.saumya.portfolio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.client.ArticlesClient;
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
        return new HttpGristClient(restClient(httpClient, props.grist().readTimeout()), mapper, props.grist().baseUrl());
    }

    @Bean
    ArticlesClient articlesClient(HttpClient httpClient, ObjectMapper mapper, PortfolioProperties props) {
        return new HttpArticlesClient(restClient(httpClient, props.articles().readTimeout()), mapper,
                props.articles().baseUrl());
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
                props.articles().maxConcurrent(), props.articles().lookupDeadline());
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
