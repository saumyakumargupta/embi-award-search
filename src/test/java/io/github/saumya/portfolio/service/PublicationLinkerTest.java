package io.github.saumya.portfolio.service;

import io.github.saumya.portfolio.domain.Article;
import io.github.saumya.portfolio.domain.Article.FundingStatement;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.service.PublicationLinker.Association;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationLinkerTest {

    private static Grant grant(String id, String funder) {
        return new Grant(id, "t", funder, null, "University of X", null, null, null, null);
    }

    private static Article article(FundingStatement... statements) {
        return new Article("MED", "1", "1", null, null, "title", "journal", "2020", List.of(statements));
    }

    @Test
    void sameIdAndSameFunderIsConfirmed() {
        var link = PublicationLinker.classify(grant("098051", "Wellcome Trust"),
                article(new FundingStatement("Royal Society", "UF1"), new FundingStatement("Wellcome Trust", "098051")));

        assertThat(link.association()).isEqualTo(Association.CONFIRMED);
        assertThat(link.matchedAgency()).isEqualTo("Wellcome Trust");
    }

    @Test
    void sameIdFromDifferentFunderIsNotTreatedAsVerified() {
        // Short numeric IDs collide across funders.
        var link = PublicationLinker.classify(grant("12345", "Wellcome Trust"),
                article(new FundingStatement("Cancer Research UK", "12345")));

        assertThat(link.association()).isEqualTo(Association.UNCERTAIN_FUNDER_MISMATCH);
    }

    @Test
    void sameIdWithoutAgencyIsUncertain() {
        var link = PublicationLinker.classify(grant("098051", "Wellcome Trust"),
                article(new FundingStatement(null, "098051")));

        assertThat(link.association()).isEqualTo(Association.UNCERTAIN_FUNDER_UNKNOWN);
    }

    @Test
    void searchHitWithoutExactIdIsUncertain() {
        var link = PublicationLinker.classify(grant("098051", "Wellcome Trust"),
                article(new FundingStatement("Wellcome Trust", "098051/Z/12/Z-extra")));

        assertThat(link.association()).isEqualTo(Association.UNCERTAIN_NO_EXACT_GRANT_ID);
    }

    @Test
    void noFundingMetadataIsUncertain() {
        assertThat(PublicationLinker.classify(grant("098051", "Wellcome Trust"), article()).association())
                .isEqualTo(Association.UNCERTAIN_NO_EXACT_GRANT_ID);
    }

    @Test
    void wellcomeDoiFormAndCasingStillMatch() {
        var link = PublicationLinker.classify(grant("ab123", "Wellcome"),
                article(new FundingStatement("Wellcome Trust", "10.35802/AB123")));

        assertThat(link.association()).isEqualTo(Association.CONFIRMED);
    }

    @Test
    void strongestStatementWins() {
        var link = PublicationLinker.classify(grant("A1", "Medical Research Council"),
                article(new FundingStatement("NIH", "A1"), new FundingStatement("Medical Research Council", "A1")));

        assertThat(link.association()).isEqualTo(Association.CONFIRMED);
    }
}
