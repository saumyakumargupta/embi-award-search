package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoGristClientTest {

    private final DemoGristClient client =
            new DemoGristClient(new ObjectMapper(), DemoGristClient.DEFAULT_FIXTURE);

    @Test
    void filtersBundledRecordsByAffiliationPhrase() {
        GrantSearchResult result = client.search("aff:\"University of Manchester\"", 1);

        assertThat(result.grants()).isNotEmpty();
        assertThat(result.hitCount()).isEqualTo((long) result.grants().size());
        assertThat(result.grants()).allSatisfy(g -> {
            assertThat(g.institutionName()).isEqualTo("University of Manchester");
            assertThat(g.hasId()).isTrue();
            assertThat(g.funderName()).isNotBlank();
        });
    }

    @Test
    void matchingIsCaseInsensitiveAndPartialLikeGrist() {
        GrantSearchResult result = client.search("aff:\"cambridge\"", 1);

        assertThat(result.grants()).extracting(Grant::institutionName).containsOnly("University of Cambridge");
    }

    @Test
    void unknownInstitutionGivesEmptyResult() {
        GrantSearchResult result = client.search("aff:\"University of Nowhere\"", 1);

        assertThat(result.grants()).isEmpty();
        assertThat(result.hitCount()).isZero();
        assertThat(client.isDemo()).isTrue();
    }
}
