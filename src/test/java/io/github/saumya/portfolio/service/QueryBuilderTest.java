package io.github.saumya.portfolio.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryBuilderTest {

    @Test
    void wrapsPlainNameInAffiliationPhrase() {
        String name = QueryBuilder.normaliseInstitution("University of Manchester");
        assertThat(QueryBuilder.gristAffiliationQuery(name)).isEqualTo("aff:\"University of Manchester\"");
    }

    @Test
    void collapsesWhitespaceAndControlCharacters() {
        assertThat(QueryBuilder.normaliseInstitution("  University\tof\n  Cambridge  "))
                .isEqualTo("University of Cambridge");
    }

    @Test
    void userCannotBreakOutOfThePhrase() {
        // Without cleaning, this would become aff:"Oxford" OR ga:"x" and change the query's meaning.
        String name = QueryBuilder.normaliseInstitution("Oxford\" OR ga:\"x\\");
        String query = QueryBuilder.gristAffiliationQuery(name);

        assertThat(query).isEqualTo("aff:\"Oxford OR ga: x\"");
        assertThat(query.chars().filter(c -> c == '"').count()).isEqualTo(2);
    }

    @Test
    void keepsAccentsAndAmpersands() {
        assertThat(QueryBuilder.normaliseInstitution("Université Paris-Saclay"))
                .isEqualTo("Université Paris-Saclay");
        assertThat(QueryBuilder.normaliseInstitution("Texas A&M University"))
                .isEqualTo("Texas A&M University");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "x", "\"\"", "&&--"})
    void rejectsNamesWithNothingSearchable(String raw) {
        assertThatThrownBy(() -> QueryBuilder.normaliseInstitution(raw))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsOverlongNames() {
        assertThatThrownBy(() -> QueryBuilder.normaliseInstitution("a".repeat(201)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("200");
    }

    @Test
    void escapesGrantIdForArticlesQuery() {
        assertThat(QueryBuilder.articlesGrantIdQuery(" 098051 ")).isEqualTo("GRANT_ID:\"098051\"");
        assertThat(QueryBuilder.articlesGrantIdQuery("AB\"12")).isEqualTo("GRANT_ID:\"AB\\\"12\"");
    }
}
