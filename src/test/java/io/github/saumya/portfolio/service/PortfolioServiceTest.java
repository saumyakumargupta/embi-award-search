package io.github.saumya.portfolio.service;

import io.github.saumya.portfolio.api.PortfolioResponse;
import io.github.saumya.portfolio.api.PortfolioResponse.GrantEntry;
import io.github.saumya.portfolio.api.PortfolioResponse.LookupStatus;
import io.github.saumya.portfolio.client.ArticlesClient;
import io.github.saumya.portfolio.client.GristClient;
import io.github.saumya.portfolio.client.UpstreamException;
import io.github.saumya.portfolio.domain.Article;
import io.github.saumya.portfolio.domain.Article.FundingStatement;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import io.github.saumya.portfolio.service.PublicationLinker.Association;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Orchestration tests with in-memory fakes in place of Europe PMC. */
class PortfolioServiceTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static Grant grant(String id) {
        return new Grant(id, "Grant " + id, "Wellcome Trust", null, "University of Cambridge", null, null, null, null);
    }

    private static Article paper(String id, String grantId) {
        return new Article("MED", id, id, null, "10.1/" + id, "Paper " + id, "J", "2021",
                List.of(new FundingStatement("Wellcome Trust", grantId)));
    }

    private static String grantIdOf(String query) {
        return query.substring("GRANT_ID:\"".length(), query.length() - 1);
    }

    private PortfolioService service(List<Grant> grants, Function<String, ArticleSearchResult> articles,
                                     int maxConcurrent, Duration deadline) {
        GristClient grist = q -> new GrantSearchResult((long) grants.size() + 100, grants);
        ArticlesClient articlesClient = (q, size) -> articles.apply(grantIdOf(q));
        return new PortfolioService(grist, articlesClient, executor, maxConcurrent, deadline);
    }

    @Test
    void resultsStayUnderTheirGrantWhateverOrderLookupsFinishIn() {
        List<Grant> grants = List.of(grant("G1"), grant("G2"), grant("G3"), grant("G4"));
        // Earlier grants answer slowest, so completion order is the reverse of Grist order.
        Map<String, Integer> delayMs = Map.of("G1", 300, "G2", 200, "G3", 100, "G4", 0);
        List<String> completionOrder = new ArrayList<>();

        PortfolioService service = service(grants, id -> {
            sleep(delayMs.get(id));
            synchronized (completionOrder) {
                completionOrder.add(id);
            }
            return new ArticleSearchResult(1, List.of(paper("P-" + id, id)));
        }, 4, Duration.ofSeconds(5));

        PortfolioResponse response = service.buildPortfolio("University of Cambridge", 4, 3);

        assertThat(completionOrder).containsExactly("G4", "G3", "G2", "G1");
        assertThat(response.grants()).extracting(GrantEntry::grantId).containsExactly("G1", "G2", "G3", "G4");
        for (GrantEntry entry : response.grants()) {
            assertThat(entry.publications()).singleElement()
                    .satisfies(p -> assertThat(p.id()).isEqualTo("P-" + entry.grantId()));
        }
    }

    @Test
    void oneFailedLookupDoesNotAffectOthersAndIsDistinctFromNoneFound() {
        List<Grant> grants = List.of(grant("OK"), grant("BROKEN"), grant("EMPTY"), grant("SLOW"));
        PortfolioService service = service(grants, id -> switch (id) {
            case "OK" -> new ArticleSearchResult(1, List.of(paper("P1", "OK")));
            case "BROKEN" -> throw new UpstreamException("Europe PMC Articles API",
                    UpstreamException.Kind.HTTP_ERROR, 500, "Europe PMC Articles API returned HTTP 500", null);
            case "SLOW" -> {
                sleep(2_000);
                yield new ArticleSearchResult(1, List.of(paper("P2", "SLOW")));
            }
            default -> new ArticleSearchResult(0, List.of());
        }, 4, Duration.ofMillis(500));

        PortfolioResponse response = service.buildPortfolio("University of Cambridge", 4, 3);

        assertThat(response.grants()).extracting(e -> e.publicationLookup().status()).containsExactly(
                LookupStatus.PUBLICATIONS_FOUND,
                LookupStatus.LOOKUP_FAILED,
                LookupStatus.NO_PUBLICATIONS_FOUND,
                LookupStatus.LOOKUP_FAILED);
        assertThat(response.grants().get(1).publicationLookup().error()).contains("HTTP 500");
        assertThat(response.grants().get(3).publicationLookup().error()).contains("timed out");
        assertThat(response.grants().get(0).publications()).hasSize(1);
        assertThat(response.summary().grantsByLookupStatus())
                .containsEntry(LookupStatus.LOOKUP_FAILED, 2)
                .containsEntry(LookupStatus.NO_PUBLICATIONS_FOUND, 1);
    }

    @Test
    void lookupsOverlapButNeverExceedTheConcurrencyLimit() {
        List<Grant> grants = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            grants.add(grant("G" + i));
        }
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        PortfolioService service = service(grants, id -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            sleep(100);
            inFlight.decrementAndGet();
            return new ArticleSearchResult(0, List.of());
        }, 3, Duration.ofSeconds(10));

        long start = System.nanoTime();
        service.buildPortfolio("University of Cambridge", 10, 1);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(maxInFlight.get()).isEqualTo(3);
        // 10 lookups of 100 ms with 3 permits take ~400 ms; sequentially they would take ~1000 ms.
        assertThat(elapsedMs).isLessThan(900);
    }

    @Test
    void ambiguousMatchesAreLabelledAndSamePaperMayAppearUnderTwoGrants() {
        Article shared = new Article("MED", "S1", "S1", null, null, "Shared", "J", "2022", List.of(
                new FundingStatement("Wellcome Trust", "A"), new FundingStatement("Cancer Research UK", "B")));
        PortfolioService service = service(List.of(grant("A"), grant("B")),
                id -> new ArticleSearchResult(1, List.of(shared, shared)), 2, Duration.ofSeconds(5));

        PortfolioResponse response = service.buildPortfolio("University of Cambridge", 2, 5);

        GrantEntry a = response.grants().get(0);
        GrantEntry b = response.grants().get(1);
        // Duplicate within a grant is dropped; the same paper under two grants is kept under both.
        assertThat(a.publications()).hasSize(1);
        assertThat(b.publications()).hasSize(1);
        assertThat(a.publications().get(0).association()).isEqualTo(Association.CONFIRMED);
        // Grant B is a Wellcome grant in Grist, but the paper attributes ID "B" to CRUK.
        assertThat(b.publications().get(0).association()).isEqualTo(Association.UNCERTAIN_FUNDER_MISMATCH);
        assertThat(b.publicationLookup().status()).isEqualTo(LookupStatus.UNCERTAIN_ASSOCIATION);

        assertThat(response.summary().publicationAssociations()).isEqualTo(2);
        assertThat(response.summary().uniquePublications()).isEqualTo(1);
        assertThat(response.summary().confirmedAssociations()).isEqualTo(1);
    }

    @Test
    void grantsWithoutIdAreNotSearchedAndEmptyInstitutionSearchIsNotAnError() {
        Grant noId = new Grant(null, "Untitled", null, null, null, null, null, null, null);
        Map<String, Integer> calls = new ConcurrentHashMap<>();
        PortfolioService service = service(List.of(noId), id -> {
            calls.merge(id, 1, Integer::sum);
            return new ArticleSearchResult(0, List.of());
        }, 2, Duration.ofSeconds(5));

        PortfolioResponse response = service.buildPortfolio("Somewhere", 5, 3);
        assertThat(calls).isEmpty();
        assertThat(response.grants().get(0).publicationLookup().status())
                .isEqualTo(LookupStatus.NOT_SEARCHED_NO_GRANT_ID);
        assertThat(response.summary().grantsByFunder()).containsEntry("(unknown funder)", 1);

        PortfolioResponse empty = new PortfolioService(q -> new GrantSearchResult(0L, List.of()),
                (q, s) -> { throw new AssertionError("should not be called"); },
                executor, 2, Duration.ofSeconds(1)).buildPortfolio("Nowhere University", 5, 3);
        assertThat(empty.grants()).isEmpty();
        assertThat(empty.grantSearch().truncated()).isFalse();
        assertThat(empty.notes()).anySatisfy(n -> assertThat(n).startsWith("No grants matched"));
    }

    @Test
    void returnsAtMostLimitGrantsInGristOrder() {
        List<Grant> grants = List.of(grant("G1"), grant("G2"), grant("G3"));
        PortfolioResponse response = service(grants, id -> new ArticleSearchResult(0, List.of()), 2,
                Duration.ofSeconds(5)).buildPortfolio("University of Cambridge", 2, 1);

        assertThat(response.grants()).extracting(GrantEntry::grantId).containsExactly("G1", "G2");
        assertThat(response.grantSearch().truncated()).isTrue();
    }

    @Test
    void rejectsOutOfRangeLimits() {
        PortfolioService service = service(List.of(), id -> null, 1, Duration.ofSeconds(1));
        assertThatThrownBy(() -> service.buildPortfolio("Leeds", 11, 3)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.buildPortfolio("Leeds", 0, 3)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.buildPortfolio("Leeds", 5, 6)).isInstanceOf(InvalidRequestException.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
