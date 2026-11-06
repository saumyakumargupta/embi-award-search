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
    public record Request(
            String institution,
            String normalisedInstitution,
            String grantsQuery,
            int limit,
            int publicationsPerGrant) {
    }

    /** Facts about the Grist search itself. */
    public record GrantSearch(
            Long totalHitCount,
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
     * Institution as reported on the grant record. {@code nameMatch} says whether the reported name equals
     * the requested name (ignoring case) or only contains/relates to it via Grist's affiliation search.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Institution(String name, String department, NameMatch nameMatch) {
    }

    public enum NameMatch { EXACT, PARTIAL, UNKNOWN }

    /** Outcome of the Articles API lookup for one grant. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicationLookup(
            LookupStatus status,
            String query,
            Long totalHitCount,
            String error) {
    }

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
