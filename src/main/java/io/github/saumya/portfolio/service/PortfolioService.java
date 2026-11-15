package io.github.saumya.portfolio.service;

import io.github.saumya.portfolio.api.PortfolioResponse;
import io.github.saumya.portfolio.api.PortfolioResponse.Funder;
import io.github.saumya.portfolio.api.PortfolioResponse.GrantEntry;
import io.github.saumya.portfolio.api.PortfolioResponse.Institution;
import io.github.saumya.portfolio.api.PortfolioResponse.LookupStatus;
import io.github.saumya.portfolio.api.PortfolioResponse.Publication;
import io.github.saumya.portfolio.api.PortfolioResponse.PublicationLookup;
import io.github.saumya.portfolio.client.ArticlesClient;
import io.github.saumya.portfolio.client.GristClient;
import io.github.saumya.portfolio.client.UpstreamException;
import io.github.saumya.portfolio.domain.Article;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import io.github.saumya.portfolio.service.GrantGrouper.GroupedGrant;
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
 * Builds an institution portfolio: a Grist search, then one Articles API lookup per distinct grant.
 *
 * <p><b>Grants.</b> Grist returns one record per person on a grant, so the same grant can appear many
 * times on a page. Records are merged into distinct grants by {@link GrantGrouper}, in Grist order. If a
 * page does not yield enough distinct grants, further pages are read, up to {@code maxGristPages}.
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
    private final int maxGristPages;

    public static final int DEFAULT_MAX_GRIST_PAGES = 3;

    public PortfolioService(GristClient gristClient,
                            ArticlesClient articlesClient,
                            ExecutorService executor,
                            int maxConcurrentArticleLookups,
                            Duration lookupDeadline) {
        this(gristClient, articlesClient, executor, maxConcurrentArticleLookups, lookupDeadline,
                DEFAULT_MAX_GRIST_PAGES);
    }

    public PortfolioService(GristClient gristClient,
                            ArticlesClient articlesClient,
                            ExecutorService executor,
                            int maxConcurrentArticleLookups,
                            Duration lookupDeadline,
                            int maxGristPages) {
        if (maxConcurrentArticleLookups < 1) {
            throw new IllegalArgumentException("maxConcurrentArticleLookups must be >= 1");
        }
        if (maxGristPages < 1) {
            throw new IllegalArgumentException("maxGristPages must be >= 1");
        }
        this.maxGristPages = maxGristPages;
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

        GristPages found = fetchDistinctGrants(gristQuery, normalised, limit);
        return respond(new PortfolioResponse.Request(institution, normalised, null, gristQuery, limit,
                publicationsPerGrant), found, found.grants(), limit, publicationsPerGrant, List.of());
    }

    /**
     * Looks up one grant number. Searches the Grants API with {@code gid:"<id>"} to find the grant's
     * funder (needed to confirm papers), keeps only grants whose ID is exactly the one asked for, and then
     * runs the same paper lookups as an institution search.
     *
     * <p>Grant numbers are not unique across funders, so several grants can come back, one per funder.
     * Each is shown separately, with its own papers and checks.
     */
    public PortfolioResponse buildGrantPortfolio(String grantId, int publicationsPerGrant) {
        if (publicationsPerGrant < 1 || publicationsPerGrant > MAX_PUBLICATIONS_PER_GRANT) {
            throw new InvalidRequestException("publicationsPerGrant",
                    "publicationsPerGrant must be between 1 and " + MAX_PUBLICATIONS_PER_GRANT);
        }
        String normalised = QueryBuilder.normaliseGrantId(grantId);
        String gristQuery = QueryBuilder.gristGrantIdQuery(normalised);

        GristPages found = fetchDistinctGrants(gristQuery, null, MAX_GRANTS);
        // gid: is a word search, so it can also return grants whose ID only contains the one asked for.
        String wanted = PublicationLinker.normaliseId(normalised);
        List<GroupedGrant> exact = new ArrayList<>();
        List<String> nearMisses = new ArrayList<>();
        for (GroupedGrant g : found.grants()) {
            String id = g.representative().id();
            if (id != null && PublicationLinker.normaliseId(id).equals(wanted)) {
                exact.add(g);
            } else if (id != null && nearMisses.size() < 5) {
                nearMisses.add(id);
            }
        }
        return respond(new PortfolioResponse.Request(null, null, normalised, gristQuery, MAX_GRANTS,
                publicationsPerGrant), found, exact, MAX_GRANTS, publicationsPerGrant, nearMisses);
    }

    /** Shared second half of both searches: paper lookups, summary and notes. */
    private PortfolioResponse respond(PortfolioResponse.Request request, GristPages found,
                                      List<GroupedGrant> candidates, int limit, int publicationsPerGrant,
                                      List<String> nearMisses) {
        List<GroupedGrant> grants = candidates.subList(0, Math.min(limit, candidates.size()));
        List<GrantEntry> entries = lookUpPublications(grants, publicationsPerGrant);

        boolean truncated = candidates.size() > grants.size()
                || (found.hitCount() != null && found.records() < found.hitCount());

        return new PortfolioResponse(
                request,
                new PortfolioResponse.GrantSearch(
                        gristClient.isDemo() ? "DEMO_FIXTURE" : "EUROPE_PMC_GRANTS_API",
                        found.hitCount(), found.pages(), found.records(), candidates.size(),
                        grants.size(), truncated),
                entries,
                summarise(entries),
                notes(gristClient.isDemo(), request.grantId() != null, found, grants.size(), limit, truncated,
                        entries, nearMisses));
    }

    /** What was read from Grist: totals, and the distinct grants in Grist order. */
    record GristPages(Long hitCount, int pages, int records, List<GroupedGrant> grants, String pagingError) {
    }

    /**
     * Reads Grist pages until there are more than {@code limit} distinct grants (so the last grant shown
     * has all its records and we know whether more exist), the results run out, or {@code maxGristPages}
     * pages have been read. A failure on page 1 fails the request (502/504); a failure on a later page
     * stops paging and keeps what was already found.
     */
    private GristPages fetchDistinctGrants(String gristQuery, String requestedName, int limit) {
        List<Grant> records = new ArrayList<>();
        List<GroupedGrant> grouped = List.of();
        Long hitCount = null;
        int pagesRead = 0;
        String pagingError = null;

        for (int page = 1; page <= maxGristPages; page++) {
            GrantSearchResult result;
            try {
                result = gristClient.search(gristQuery, page);
            } catch (UpstreamException e) {
                if (page == 1) {
                    throw e;
                }
                log.warn("Stopped reading Grants API pages at page {}: {}", page, e.getMessage());
                pagingError = e.getMessage();
                break;
            }
            pagesRead = page;
            if (hitCount == null) {
                hitCount = result.hitCount();
            }
            records.addAll(result.grants());
            grouped = GrantGrouper.group(records, requestedName);

            boolean lastPage = result.grants().size() < GristClient.PAGE_SIZE
                    || (hitCount != null && records.size() >= hitCount);
            if (grouped.size() > limit || lastPage) {
                break;
            }
        }
        return new GristPages(hitCount, pagesRead, records.size(), grouped, pagingError);
    }

    private List<GrantEntry> lookUpPublications(List<GroupedGrant> grants, int perGrant) {
        // Submit everything first so lookups overlap; the semaphore bounds actual parallelism.
        List<Future<GrantEntry>> futures = new ArrayList<>(grants.size());
        for (int i = 0; i < grants.size(); i++) {
            GroupedGrant grant = grants.get(i);
            int rank = i + 1;
            futures.add(executor.submit(() -> lookUp(rank, grant, perGrant)));
        }

        long deadlineNanos = System.nanoTime() + lookupDeadline.toNanos();
        List<GrantEntry> entries = new ArrayList<>(grants.size());
        for (int i = 0; i < futures.size(); i++) {
            Future<GrantEntry> future = futures.get(i);
            GroupedGrant grant = grants.get(i);
            String query = grant.representative().hasId()
                    ? QueryBuilder.articlesGrantIdQuery(grant.representative().id()) : null;
            try {
                long remaining = Math.max(0, deadlineNanos - System.nanoTime());
                entries.add(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                entries.add(failed(i + 1, grant, query, "Publication lookup timed out"));
            } catch (ExecutionException e) {
                // lookUp() catches upstream errors itself; anything here is unexpected.
                log.warn("Unexpected failure looking up publications for grant {}",
                        grant.representative().id(), e.getCause());
                entries.add(failed(i + 1, grant, query, "Publication lookup failed unexpectedly"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("Interrupted while waiting for publication lookups", e);
            }
        }
        return entries;
    }

    /** Runs on a worker thread. Never throws for upstream problems: they become LOOKUP_FAILED. */
    private GrantEntry lookUp(int rank, GroupedGrant grouped, int perGrant) throws InterruptedException {
        Grant grant = grouped.representative();
        if (!grant.hasId()) {
            return entry(rank, grouped,
                    new PublicationLookup(LookupStatus.NOT_SEARCHED_NO_GRANT_ID, null, null, null, null), List.of());
        }
        String query = QueryBuilder.articlesGrantIdQuery(grant.id());

        ArticleSearchResult result;
        articlePermits.acquire();
        try {
            result = articlesClient.search(query, perGrant);
        } catch (UpstreamException e) {
            log.warn("Articles lookup failed for grant {}: {} ({})", grant.id(), e.getMessage(),
                    e.getCause() == null ? "no cause" : e.getCause().toString());
            return failed(rank, grouped, query, e.getMessage());
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
        return entry(rank, grouped,
                new PublicationLookup(status,
                        result.fromFixture() ? PortfolioResponse.SOURCE_SAVED_ARTICLES : PortfolioResponse.SOURCE_LIVE_ARTICLES,
                        query, result.hitCount(), null), publications);
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

    private GrantEntry failed(int rank, GroupedGrant grant, String query, String error) {
        return entry(rank, grant,
                new PublicationLookup(LookupStatus.LOOKUP_FAILED, null, query, null, error), List.of());
    }

    private static GrantEntry entry(int rank, GroupedGrant grouped,
                                    PublicationLookup lookup, List<Publication> publications) {
        Grant grant = grouped.representative();
        Funder funder = grant.funderName() == null && grant.funderFundRefId() == null
                ? null : new Funder(grant.funderName(), grant.funderFundRefId());
        Institution institution = new Institution(grant.institutionName(), grant.institutionDepartment(),
                grant.institutionRorId(), grouped.nameMatch());
        return new GrantEntry(rank, grant.id(), grant.title(), funder, institution,
                grouped.institutions().isEmpty() ? null : grouped.institutions(),
                grant.principalInvestigator(), grant.startDate(), grant.endDate(), lookup, publications);
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

    private static List<String> notes(boolean demo, boolean byGrantId, GristPages found, int returned, int limit,
                                      boolean truncated, List<GrantEntry> entries, List<String> nearMisses) {
        boolean noGrants = returned == 0;
        List<String> notes = new ArrayList<>();
        if (demo) {
            notes.add("DEMO MODE: grants come from a bundled fixture, not the live Europe PMC Grants API. "
                    + "Grant IDs and funders are real, but the institution assignment is illustrative and titles, "
                    + "PIs and dates are absent. Publications are looked up live in the Articles API.");
        }
        if (noGrants && byGrantId) {
            notes.add("No grant with exactly this ID was found in the Europe PMC Grants API, which only covers "
                    + "participating funders. Check the ID, including slashes and suffixes."
                    + (nearMisses.isEmpty() ? "" : " Similar IDs found: " + String.join(", ", nearMisses) + "."));
        } else if (noGrants) {
            notes.add("No grants matched this affiliation in the Europe PMC Grants API. Try a variant of the "
                    + "name (e.g. 'Univ of X', 'X University', or a distinctive word such as the city).");
        }
        if (byGrantId && returned > 1) {
            notes.add("Several funders use this grant ID. Each is shown as a separate grant, and papers are "
                    + "checked against each funder separately.");
        }
        if (found.records() > found.grants().size()) {
            notes.add("The Grants API returns one record per person on a grant. " + found.records()
                    + " records were merged into " + found.grants().size() + " distinct grants; "
                    + "allInstitutions lists every institution named on a grant.");
        }
        if (found.pagingError() != null) {
            notes.add("Stopped reading further Grants API pages after an error (" + found.pagingError()
                    + "), so fewer grants may be shown than requested.");
        } else if (!byGrantId && !noGrants && returned < limit && truncated) {
            notes.add("Only the first " + found.pages() + " Grants API pages were read, which held "
                    + found.grants().size() + " distinct grants.");
        }
        if (truncated && !byGrantId) {
            notes.add("More grants matched than were returned; this is a sample in Grants API order.");
        }
        List<String> saved = entries.stream()
                .filter(e -> PortfolioResponse.SOURCE_SAVED_ARTICLES.equals(e.publicationLookup().source()))
                .map(e -> e.grantId())
                .toList();
        if (!saved.isEmpty()) {
            notes.add("SAVED DATA: publications for " + saved.size() + " grant(s) come from saved Articles API "
                    + "results (publicationLookup.source = SAVED_FIXTURE), not a live call: " + String.join(", ", saved)
                    + ". They were real when captured but may be out of date.");
        }
        if (!byGrantId) {
            notes.add("The Grants API indexes awards from participating funders only, and affiliation names vary, "
                    + "so these grants are not the institution's full funding portfolio.");
        }
        notes.add("NO_PUBLICATIONS_FOUND means none were linked in Europe PMC, not that none exist.");
        return notes;
    }
}
