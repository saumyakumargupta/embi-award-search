package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GristResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private GrantSearchResult parse(String json) throws Exception {
        return GristResponseParser.parse(mapper.readTree(json));
    }

    @Test
    void readsCoreRecordsInOrder() throws Exception {
        GrantSearchResult result = parse("""
                {"HitCount": 312,
                 "RecordList": {"Record": [
                   {"Person": {"FamilyName": "Smith", "GivenName": "Jane", "Title": "Prof"},
                    "Grant": {"Funder": {"Name": "Wellcome Trust", "FundRefID": "http://dx.doi.org/10.13039/100004440"},
                              "Id": "098051", "Title": "Sanger core", "StartDate": "2012-01-01", "EndDate": "2016-12-31"},
                    "Institution": {"Name": "University of Cambridge", "Department": "Genetics"}},
                   {"Grant": {"Funder": {"Name": "MRC"}, "Id": "MR/X/1", "Title": "Second"},
                    "Institution": {"Name": "Cambridge Institute"}}
                 ]}}""");

        assertThat(result.hitCount()).isEqualTo(312L);
        assertThat(result.grants()).extracting(Grant::id).containsExactly("098051", "MR/X/1");
        Grant first = result.grants().get(0);
        assertThat(first.funderName()).isEqualTo("Wellcome Trust");
        assertThat(first.institutionName()).isEqualTo("University of Cambridge");
        assertThat(first.principalInvestigator()).isEqualTo("Prof Jane Smith");
        assertThat(first.startDate()).isEqualTo("2012-01-01");
    }

    @Test
    void acceptsSingleRecordAsObjectAndMissingFields() throws Exception {
        // XML-to-JSON converters often emit a lone repeated element as an object, not an array.
        GrantSearchResult result = parse("""
                {"HitCount": "1", "RecordList": {"Record": {"Grant": {"Title": {"$": "No id or funder"}}}}}""");

        assertThat(result.grants()).hasSize(1);
        Grant grant = result.grants().get(0);
        assertThat(grant.title()).isEqualTo("No id or funder");
        assertThat(grant.id()).isNull();
        assertThat(grant.hasId()).isFalse();
        assertThat(grant.funderName()).isNull();
        assertThat(grant.institutionName()).isNull();
        assertThat(grant.principalInvestigator()).isNull();
    }

    @Test
    void readsLiveFormatWithValueWrappersAndRorIds() throws Exception {
        // Trimmed from a real aff:"University of Manchester" response (November 2026).
        GrantSearchResult result = parse("""
                {"HitCount": "3265",
                 "Request": {"Query": "aff:\\"University of Manchester\\"", "ResultType": "Core", "Page": "1"},
                 "RecordList": {"Record": [
                   {"Person": {"FamilyName": "Trost", "GivenName": "Matthias", "Title": "Prof"},
                    "Grant": {"Funder": {"Name": "Wellcome Trust", "FundRefID": "https://doi.org/10.13039/100010269"},
                              "Id": "340947", "Title": "Type VI secretion effectors",
                              "Abstract": {"value": "Long abstract", "Language": "en", "Type": "scientific"},
                              "Amount": {"value": 201515.2, "Currency": "GBP"},
                              "StartDate": "2026-09-01", "EndDate": "2032-09-01"},
                    "Institution": {"Name": "University of Manchester", "RORID": "027m9bs27"}},
                   {"Grant": {"Funder": {"Name": "NIHR"}, "Id": "NIHR306530"},
                    "Institution": {"Name": "University of Cambridge", "RORID": "https://ror.org/013meh722"}}
                 ]}}""");

        assertThat(result.hitCount()).isEqualTo(3265L);
        Grant first = result.grants().get(0);
        assertThat(first.id()).isEqualTo("340947");
        assertThat(first.funderFundRefId()).isEqualTo("https://doi.org/10.13039/100010269");
        assertThat(first.institutionRorId()).isEqualTo("027m9bs27");
        assertThat(first.principalInvestigator()).isEqualTo("Prof Matthias Trost");
        // Both ROR spellings come out the same.
        assertThat(result.grants().get(1).institutionRorId()).isEqualTo("013meh722");
    }

    @Test
    void emptySearchHasNoRecordList() throws Exception {
        GrantSearchResult result = parse("""
                {"Request": {"Query": "aff:\\"Nowhere\\""}, "HitCount": 0}""");

        assertThat(result.hitCount()).isZero();
        assertThat(result.grants()).isEmpty();
    }
}
