package io.github.saumya.portfolio.domain;

/**
 * One record from the Europe PMC Grants (Grist) API, reduced to the fields this service uses.
 *
 * <p>Grist returns one record per person on a grant, so a grant with collaborators at several
 * institutions arrives as several records with the same funder and ID but different institutions.
 * {@link io.github.saumya.portfolio.service.GrantGrouper} merges them. Every field may be null because
 * Grist records are not guaranteed to be complete.
 */
public record Grant(
        String id,
        String title,
        String funderName,
        String funderFundRefId,
        String institutionName,
        String institutionDepartment,
        String principalInvestigator,
        String startDate,
        String endDate,
        String institutionRorId) {

    /** Form without a ROR ID, kept so callers written before ROR IDs were read still compile. */
    public Grant(String id, String title, String funderName, String funderFundRefId,
                 String institutionName, String institutionDepartment, String principalInvestigator,
                 String startDate, String endDate) {
        this(id, title, funderName, funderFundRefId, institutionName, institutionDepartment,
                principalInvestigator, startDate, endDate, null);
    }

    public boolean hasId() {
        return id != null && !id.isBlank();
    }
}
