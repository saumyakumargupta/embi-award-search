package io.github.saumya.portfolio.domain;

/**
 * A grant record as returned by the Europe PMC Grants (Grist) API, reduced to the fields this
 * service uses. Every field may be null because Grist records are not guaranteed to be complete.
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
        String endDate) {

    public boolean hasId() {
        return id != null && !id.isBlank();
    }
}
