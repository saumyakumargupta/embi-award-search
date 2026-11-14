package io.github.saumya.portfolio.service;

import io.github.saumya.portfolio.api.PortfolioResponse.NameMatch;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.service.GrantGrouper.GroupedGrant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The live Grants API returns one record per person on a grant; these records must become one grant. */
class GrantGrouperTest {

    private static Grant record(String id, String funder, String institution) {
        return new Grant(id, "Title " + id, funder, null, institution, null, null, null, null);
    }

    @Test
    void mergesPerPersonRecordsAndKeepsFirstAppearanceOrder() {
        // Shape seen live for aff:"University of Manchester": grant 352259 has 8 records, Bristol first.
        List<Grant> records = List.of(
                record("352259", "Wellcome Trust", "University of Bristol"),
                record("352259", "Wellcome Trust", "Liverpool School of Tropical Medicine"),
                record("352259", "Wellcome Trust", "University of Manchester"),
                record("352259", "Wellcome Trust", "Liverpool School of Tropical Medicine"),
                record("341725", "Wellcome Trust", "University of Manchester"));

        List<GroupedGrant> grants = GrantGrouper.group(records, "University of Manchester");

        assertThat(grants).extracting(g -> g.representative().id()).containsExactly("352259", "341725");
        GroupedGrant first = grants.get(0);
        assertThat(first.records()).isEqualTo(4);
        // The Manchester record represents the grant, not the Bristol one that came first.
        assertThat(first.representative().institutionName()).isEqualTo("University of Manchester");
        assertThat(first.nameMatch()).isEqualTo(NameMatch.EXACT);
        assertThat(first.institutions()).containsExactly(
                "University of Bristol", "Liverpool School of Tropical Medicine", "University of Manchester");
    }

    @Test
    void sameIdFromDifferentFundersStaysSeparate() {
        List<GroupedGrant> grants = GrantGrouper.group(List.of(
                record("12345", "Wellcome Trust", "University of Leeds"),
                record("12345", "Cancer Research UK", "University of Leeds")), "University of Leeds");

        assertThat(grants).hasSize(2);
    }

    @Test
    void recordsWithoutIdAreNeverMerged() {
        List<GroupedGrant> grants = GrantGrouper.group(List.of(
                record(null, "Wellcome Trust", "University of Leeds"),
                record(null, "Wellcome Trust", "University of Leeds")), "University of Leeds");

        assertThat(grants).hasSize(2);
    }

    @Test
    void nameMatchDistinguishesPartialFromAnotherInstitution() {
        assertThat(GrantGrouper.nameMatch("University of Cambridge", "university of cambridge")).isEqualTo(NameMatch.EXACT);
        assertThat(GrantGrouper.nameMatch("Cambridge", "University of Cambridge")).isEqualTo(NameMatch.PARTIAL);
        assertThat(GrantGrouper.nameMatch("University of Manchester", "University of Bristol")).isEqualTo(NameMatch.OTHER);
        assertThat(GrantGrouper.nameMatch("University of Manchester", null)).isEqualTo(NameMatch.UNKNOWN);
    }
}
