package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The saved Articles API data, and the fallback from the live API to it. */
class SavedArticlesTest {

    private final FixtureArticlesClient saved =
            new FixtureArticlesClient(new ObjectMapper(), FixtureArticlesClient.DEFAULT_FIXTURE);

    private static final ArticlesClient DOWN = (q, size) -> {
        throw new UpstreamException("Europe PMC Articles API", UpstreamException.Kind.HTTP_ERROR, 503,
                "Europe PMC Articles API returned HTTP 503", null);
    };

    @Test
    void savedDataAnswersLikeTheLiveApiButIsFlagged() {
        // MR/L011840/1 (MRC, Manchester) had 90+ papers when the data was captured.
        ArticleSearchResult result = saved.search("GRANT_ID:\"MR/L011840/1\"", 3);

        assertThat(result.fromFixture()).isTrue();
        assertThat(result.hitCount()).isGreaterThan(3);
        assertThat(result.articles()).hasSize(3);   // capped at pageSize, like the live API
        assertThat(result.articles()).allSatisfy(a -> assertThat(a.fundingStatements()).isNotEmpty());
        assertThat(saved.capturedOn()).isEqualTo("2026-11-15");
    }

    @Test
    void grantWithoutSavedDataIsAFailureNotAnEmptyResult() {
        assertThatThrownBy(() -> saved.search("GRANT_ID:\"NOT-A-SAVED-GRANT\"", 3))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("no saved Articles API data");
    }

    @Test
    void readsTheGrantIdBackOutOfTheQuery() {
        assertThat(FixtureArticlesClient.grantIdFrom("GRANT_ID:\"098051\"")).isEqualTo("098051");
        assertThat(FixtureArticlesClient.grantIdFrom("GRANT_ID:\"AB\\\"12\"")).isEqualTo("AB\"12");
        assertThat(FixtureArticlesClient.grantIdFrom("malaria")).isNull();
    }

    @Test
    void fallbackUsesLiveWheneverLiveAnswers() {
        AtomicInteger savedCalls = new AtomicInteger();
        ArticlesClient live = (q, size) -> new ArticleSearchResult(0, List.of());   // live and empty
        ArticlesClient countingSaved = (q, size) -> {
            savedCalls.incrementAndGet();
            return saved.search(q, size);
        };

        ArticleSearchResult result = new FallbackArticlesClient(live, countingSaved)
                .search("GRANT_ID:\"MR/L011840/1\"", 3);

        // An empty live answer is real information, so saved data is not consulted.
        assertThat(result.fromFixture()).isFalse();
        assertThat(result.articles()).isEmpty();
        assertThat(savedCalls).hasValue(0);
    }

    @Test
    void fallbackUsesSavedDataWhenLiveFails() {
        ArticleSearchResult result = new FallbackArticlesClient(DOWN, saved).search("GRANT_ID:\"MR/L011840/1\"", 2);

        assertThat(result.fromFixture()).isTrue();
        assertThat(result.articles()).hasSize(2);
    }

    @Test
    void fallbackReportsTheLiveFailureWhenNothingIsSaved() {
        assertThatThrownBy(() -> new FallbackArticlesClient(DOWN, saved).search("GRANT_ID:\"NOT-SAVED\"", 2))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.upstreamStatus()).isEqualTo(503);
                    assertThat(e.getMessage()).isEqualTo(
                            "Europe PMC Articles API returned HTTP 503; no saved Articles API data for this grant");
                });
    }
}
