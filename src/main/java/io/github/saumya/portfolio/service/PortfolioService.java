package io.github.saumya.portfolio.service;

import io.github.saumya.portfolio.api.PortfolioResponse;
import io.github.saumya.portfolio.api.PortfolioResponse.Funder;
import io.github.saumya.portfolio.api.PortfolioResponse.GrantEntry;
import io.github.saumya.portfolio.api.PortfolioResponse.Institution;
import io.github.saumya.portfolio.api.PortfolioResponse.LookupStatus;
import io.github.saumya.portfolio.api.PortfolioResponse.NameMatch;
import io.github.saumya.portfolio.api.PortfolioResponse.Publication;
import io.github.saumya.portfolio.api.PortfolioResponse.PublicationLookup;
import io.github.saumya.portfolio.client.ArticlesClient;
import io.github.saumya.portfolio.client.GristClient;
import io.github.saumya.portfolio.client.UpstreamException;
import io.github.saumya.portfolio.domain.Article;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import io.github.saumya.portfolio.service.PublicationLinker.Association;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds an institution portfolio: one Grist search, then one Articles API lookup per grant.
 *
 * <p><b>Concurrency.</b> Article lookups are independent, so each runs as its own task on the supplied
 * executor (virtual threads in production). A {@link Semaphore} shared by the whole service caps how many
 * Articles API calls are in flight at once, across all incoming requests, so a burst of users cannot turn
 * into a burst against Europe PMC. Results are collected by index, so each grant keeps its Grist position
 * whatever order the lookups finish in.
 *
 * <p><b>Failure isolation.</b> Each lookup has its own deadline. A failed or timed-out lookup is reported
 * as {@link LookupStatus#LOOKUP_FAILED} for that grant only; other grants are unaffected. Only a Grist
 * failure fails the whole request, because without grants there is nothing to return.
 */
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    public static final int MAX_GRANTS = 10;
    public static final int MAX_PUBLICATIONS_PER_GRANT = 5;

    private final GristClient gristClient;
    private final ArticlesClient articlesClient;
    private final ExecutorService executor;
    private final Semaphore articlePermits;
    private final Duration lookupDeadline;

    public PortfolioService(GristClient gristClient,
                            ArticlesClient articlesClient,
                            ExecutorService executor,
                            int maxConcurrentArticleLookups,
                            Duration lookupDeadline) {
        if (maxConcurrentArticleLookups < 1) {
            throw new IllegalArgumentException("maxConcurrentArticleLookups must be >= 1");
        }
        this.gristClient = gristClient;
        this.articlesClient = articlesClient;
        this.executor = executor;
        this.articlePermits = new Semaphore(maxConcurrentArticleLookups, true);
        this.lookupDeadline = lookupDeadline;
    }

    public PortfolioResponse buildPortfolio(String institution, int limit, int publicationsPerGrant) {
        if (limit < 1 || limit > MAX_GRANTS) {
            throw new InvalidRequestException("limit", "limit must be between 1 and " + MAX_GRANTS);
        }
        if (publicationsPerGrant < 1 || publicationsPerGrant > MAX_PUBLICATIONS_PER_GRANT) {
            throw new InvalidRequestException("publicationsPerGrant",
                    "publicationsPerGrant must be between 1 and " + MAX_PUBLICATIONS_PER_GRANT);
        }
        String normalised = QueryBuilder.normaliseInstitution(institution);
        String gristQuery = QueryBuilder.gristAffiliationQuery(normalised);

        // A Grist failure propagates as UpstreamException and becomes a 502/504 for the whole request.
        GrantSearchResult search = gristClient.search(gristQuery);
        List<Grant> grants = search.grants().stream().limit(limit).toList();

        List<GrantEntry> entries = lookUpPublications(grants, normalised, publicationsPerGrant);

        boolean truncated = search.hitCount() != null
                ? search.hitCount() > grants.size()
                : search.grants().size() > grants.size();

        return new PortfolioResponse(
                new PortfolioResponse.Request(institution, normalised, gristQuery, limit, publicationsPerGrant),
                new PortfolioResponse.GrantSearch(
                        gristClient.isDemo() ? "DEMO_FIXTURE" : "EUROPE_PMC_GRANTS_API",
                        search.hitCount(), grants.size(), truncated),
                entries,
                summarise(entries),
                notes(gristClient.isDemo(), grants.isEmpty(), truncated));
    }

    private List<GrantEntry> lookUpPublications(List<Grant> grants, String requestedName, int perGrant) {
        // Submit everything first so lookups overlap; the semaphore bounds actual parallelism.
        List<Future<GrantEntry>> futures = new ArrayList<>(grants.size());
        for (int i = 0; i < grants.size(); i++) {
            Grant grant = grants.get(i);
            int rank = i + 1;
            futures.add(executor.submit(() -> lookUp(rank, grant, requestedName, perGrant)));
        }

        long deadlineNanos = System.nanoTime() + lookupDeadline.toNanos();
        List<GrantEntry> entries = new ArrayList<>(grants.size());
        for (int i = 0; i < futures.size(); i++) {
            Future<GrantEntry> future = futures.get(i);
            Grant grant = grants.get(i);
            String query = grant.hasId() ? QueryBuilder.articlesGrantIdQuery(grant.id()) : null;
            try {
                long remaining = Math.max(0, deadlineNanos - System.nanoTime());
                entries.add(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                entries.add(failed(i + 1, grant, requestedName, query, "Publication lookup timed out"));
            } catch (ExecutionException e) {
                // lookUp() catches upstream errors itself; anything here is unexpected.
                log.warn("Unexpected failure looking up publications for grant {}", grant.id(), e.getCause());
                entries.add(failed(i + 1, grant, requestedName, query, "Publication lookup failed unexpectedly"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("Interrupted while waiting for publication lookups", e);
            }
        }
        return entries;
    }

    /** Runs on a worker thread. Never throws for upstream problems: they become LOOKUP_FAILED. */
    private GrantEntry lookUp(int rank, Grant grant, String requestedName, int perGrant) throws InterruptedException {
        if (!grant.hasId()) {
            return entry(rank, grant, requestedName,
                    new PublicationLookup(LookupStatus.NOT_SEARCHED_NO_GRANT_ID, null, null, null), List.of());
        }
        String query = QueryBuilder.articlesGrantIdQuery(grant.id());

        ArticleSearchResult result;
        articlePermits.acquire();
        try {
            result = articlesClient.search(query, perGrant);
        } catch (UpstreamException e) {
            log.warn("Articles lookup failed for grant {}: {} ({})", grant.id(), e.getMessage(),
                    e.getCause() == null ? "no cause" : e.getCause().toString());
            return failed(rank, grant, requestedName, query, e.getMessage());
        } finally {
            articlePermits.release();
        }

        List<Publication> publications = toPublications(grant, result.articles(), perGrant);
        LookupStatus status;
        if (publications.isEmpty()) {
            status = LookupStatus.NO_PUBLICATIONS_FOUND;
        } else if (publications.stream().anyMatch(p -> p.association() == Association.CONFIRMED)) {
            status = LookupStatus.PUBLICATIONS_FOUND;
        } else {
            status = LookupStatus.UNCERTAIN_ASSOCIATION;
        }
        return entry(rank, grant, requestedName,
                new PublicationLookup(status, query, result.hitCount(), null), publications);
    }

    /**
     * Keeps Articles API order, drops duplicates of the same source+id within this grant only, and caps
     * the list. The same paper legitimately appearing under two grants is kept under both.
     */
    static List<Publication> toPublications(Grant grant, List<Article> articles, int perGrant) {
        Set<String> seen = new LinkedHashSet<>();
        List<Publication> out = new ArrayList<>();
        for (Article article : articles) {
            if (out.size() >= perGrant) {
                break;
            }
            String key = publicationKey(article);
            if (!seen.add(key)) {
                continue;
            }
            PublicationLinker.Link link = PublicationLinker.classify(grant, article);
            out.add(new Publication(key, article.source(), article.id(), article.pmid(), article.pmcid(),
                    article.doi(), article.title(), article.journal(), article.pubYear(),
                    link.association(), link.matchedAgency()));
        }
        return out;
    }

    /** Stable identity: Europe PMC source + id (e.g. {@code MED:12345}), falling back to DOI. */
    static String publicationKey(Article article) {
        if (article.source() != null && article.id() != null) {
            return article.source() + ":" + article.id();
        }
        if (article.doi() != null) {
            return "DOI:" + article.doi().toLowerCase(Locale.ROOT);
        }
        return "UNKNOWN:" + System.identityHashCode(article);
    }

    private GrantEntry failed(int rank, Grant grant, String requestedName, String query, String error) {
        return entry(rank, grant, requestedName,
                new PublicationLookup(LookupStatus.LOOKUP_FAILED, query, null, error), List.of());
    }

    private static GrantEntry entry(int rank, Grant grant, String requestedName,
                                    PublicationLookup lookup, List<Publication> publications) {
        Funder funder = grant.funderName() == null && grant.funderFundRefId() == null
                ? null : new Funder(grant.funderName(), grant.funderFundRefId());
        Institution institution = new Institution(grant.institutionName(), grant.institutionDepartment(),
                nameMatch(requestedName, grant.institutionName()));
        return new GrantEntry(rank, grant.id(), grant.title(), funder, institution,
                grant.principalInvestigator(), grant.startDate(), grant.endDate(), lookup, publications);
    }

    static NameMatch nameMatch(String requested, String reported) {
        if (reported == null) {
            return NameMatch.UNKNOWN;
        }
        return reported.trim().equalsIgnoreCase(requested) ? NameMatch.EXACT : NameMatch.PARTIAL;
    }

    private static PortfolioResponse.Summary summarise(List<GrantEntry> entries) {
        Map<String, Integer> byFunder = new LinkedHashMap<>();
        Map<LookupStatus, Integer> byStatus = new EnumMap<>(LookupStatus.class);
        int associations = 0;
        int confirmed = 0;
        Set<String> unique = new LinkedHashSet<>();
        for (GrantEntry e : entries) {
            String funder = e.funder() != null && e.funder().name() != null ? e.funder().name() : "(unknown funder)";
            byFunder.merge(funder, 1, Integer::sum);
            byStatus.merge(e.publicationLookup().status(), 1, Integer::sum);
            for (Publication p : e.publications()) {
                associations++;
                if (p.association() == Association.CONFIRMED) {
                    confirmed++;
                }
                unique.add(p.key());
            }
        }
        return new PortfolioResponse.Summary(
                "Counts describe only the grants and publications returned in this response, "
                        + "not the institution's complete funding or publication record.",
                entries.size(), byFunder, byStatus, associations, confirmed, associations - confirmed, unique.size());
    }

    private static List<String> notes(boolean demo, boolean noGrants, boolean truncated) {
        List<String> notes = new ArrayList<>();
        if (demo) {
            notes.add("DEMO MODE: grants come from a bundled fixture, not the live Europe PMC Grants API. "
                    + "Grant IDs and funders are real, but the institution assignment is illustrative and titles, "
                    + "PIs and dates are absent. Publications are looked up live in the Articles API.");
        }
        if (noGrants) {
            notes.add("No grants matched this affiliation in the Europe PMC Grants API. Try a variant of the "
                    + "name (e.g. 'Univ of X', 'X University', or a distinctive word such as the city).");
        }
        if (truncated) {
            notes.add("More grants matched than were returned; this is a sample in Grants API order.");
        }
        notes.add("The Grants API indexes awards from participating funders only, and affiliation names vary, "
                + "so these grants are not the institution's full funding portfolio.");
        notes.add("NO_PUBLICATIONS_FOUND means none were linked in Europe PMC, not that none exist.");
        return notes;
    }
}
