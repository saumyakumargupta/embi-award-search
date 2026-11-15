package io.github.saumya.portfolio.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.saumya.portfolio.service.PublicationLinker.Association;

import java.util.List;
import java.util.Map;

/** JSON body returned by {@code GET /institution-portfolio}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortfolioResponse(
        Request request,
        GrantSearch grantSearch,
        List<GrantEntry> grants,
        Summary summary,
        List<String> notes) {

    /** What was asked for, and the exact upstream query it became. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            String institution,
            String normalisedInstitution,
            String grantId,
            String grantsQuery,
            int limit,
            int publicationsPerGrant) {
    }

    /**
     * Facts about the Grist search itself.
     *
     * @param source              {@code EUROPE_PMC_GRANTS_API}, or {@code DEMO_FIXTURE} in demo mode
     * @param totalHitCount       Grist's HitCount. Grist counts records (one per person on a grant),
     *                            so this is larger than the number of distinct grants
     * @param pagesFetched        Grist pages read to collect enough distinct grants (25 records per page)
     * @param recordsFetched      records read across those pages
     * @param distinctGrantsFound distinct grants among those records, after merging per-person records
     * @param returned            grants in this response ({@code <= limit})
     * @param truncated           true when more matching grants exist than were returned
     */
    public record GrantSearch(
            String source,
            Long totalHitCount,
            int pagesFetched,
            int recordsFetched,
            int distinctGrantsFound,
            int returned,
            boolean truncated) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GrantEntry(
            int rank,
            String grantId,
            String title,
            Funder funder,
            Institution institution,
            List<String> allInstitutions,
            String principalInvestigator,
            String startDate,
            String endDate,
            PublicationLookup publicationLookup,
            List<Publication> publications) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Funder(String name, String fundRefId) {
    }

    /**
     * The institution on the grant record chosen to represent the grant: the one that best matches the
     * requested name. {@code rorId} is the Research Organization Registry ID when Grist provides it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Institution(String name, String department, String rorId, NameMatch nameMatch) {
    }

    /**
     * How the representative institution relates to the requested name. Declared best-first.
     * OTHER means even the best record on this grant names a different institution (for example a
     * collaborator's), so the grant matched the affiliation search through another field or person.
     */
    public enum NameMatch { EXACT, PARTIAL, OTHER, UNKNOWN }

    /**
     * Outcome of the Articles API lookup for one grant. {@code source} says where the publications came
     * from: {@code EUROPE_PMC_ARTICLES_API} (a live call) or {@code SAVED_FIXTURE} (saved Articles API data,
     * used when the live call failed in fallback mode, or always in demo mode). It is absent when the
     * lookup failed or was not attempted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicationLookup(
            LookupStatus status,
            String source,
            String query,
            Long totalHitCount,
            String error) {
    }

    public static final String SOURCE_LIVE_ARTICLES = "EUROPE_PMC_ARTICLES_API";
    public static final String SOURCE_SAVED_ARTICLES = "SAVED_FIXTURE";

    public enum LookupStatus {
        /** At least one returned publication is a confirmed association. */
        PUBLICATIONS_FOUND,
        /** Publications were returned but none could be confirmed against this grant's funder. */
        UNCERTAIN_ASSOCIATION,
        /** The lookup succeeded and found nothing. This does not prove the grant produced no papers. */
        NO_PUBLICATIONS_FOUND,
        /** The lookup failed (timeout, upstream error). Absence of publications is unknown. */
        LOOKUP_FAILED,
        /** The grant record has no ID, so there is nothing to search for. */
        NOT_SEARCHED_NO_GRANT_ID
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Publication(
            String key,
            String source,
            String id,
            String pmid,
            String pmcid,
            String doi,
            String title,
            String journal,
            String pubYear,
            Association association,
            String matchedFunderAgency) {
    }

    /** Counts over the returned sample only. These are not institutional totals. */
    public record Summary(
            String scope,
            int grantsReturned,
            Map<String, Integer> grantsByFunder,
            Map<LookupStatus, Integer> grantsByLookupStatus,
            int publicationAssociations,
            int confirmedAssociations,
            int uncertainAssociations,
            int uniquePublications) {
    }
}
