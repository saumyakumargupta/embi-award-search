package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** URL construction and error mapping for the HTTP clients, against a mock server (no network). */
class HttpClientsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void gristQueryIsPercentEncodedIntoThePath() {
        HttpGristClient client = new HttpGristClient(RestClient.create(), mapper, "https://example.org/GristAPI/");

        URI uri = client.buildUri("aff:\"Texas A&M University\"");

        // & inside the name must not be read as Grist's parameter separator.
        assertThat(uri.toString()).isEqualTo("https://example.org/GristAPI/rest/get/query="
                + "aff:%22Texas%20A%26M%20University%22&resultType=core&format=json&page=1");
    }

    @Test
    void articlesQueryIsStrictlyEncoded() {
        HttpArticlesClient client = new HttpArticlesClient(RestClient.create(), mapper, "https://example.org/rest");

        URI uri = client.buildUri("GRANT_ID:\"A+B&C\"", 3);

        assertThat(uri.toString()).isEqualTo("https://example.org/rest/search?query=GRANT_ID%3A%22A%2BB%26C%22"
                + "&format=json&resultType=core&pageSize=3");
    }

    @Test
    void parsesArticlesResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://example.org/rest/search")))
                .andRespond(withSuccess("""
                        {"hitCount": 42, "resultList": {"result": [
                          {"id": "123", "source": "MED", "pmid": "123", "doi": "10.1/x", "title": "T",
                           "pubYear": "2020", "journalInfo": {"journal": {"title": "eLife"}},
                           "grantsList": {"grant": [{"agency": "Wellcome Trust", "grantId": "098051"}]}},
                          {"id": "PPR1", "source": "PPR", "title": "Preprint",
                           "bookOrReportDetails": {"publisher": "bioRxiv"}}
                        ]}}""", MediaType.APPLICATION_JSON));

        ArticleSearchResult result = new HttpArticlesClient(builder.build(), mapper, "https://example.org/rest")
                .search("GRANT_ID:\"098051\"", 3);

        assertThat(result.hitCount()).isEqualTo(42);
        assertThat(result.articles()).hasSize(2);
        assertThat(result.articles().get(0).journal()).isEqualTo("eLife");
        assertThat(result.articles().get(0).fundingStatements()).hasSize(1);
        assertThat(result.articles().get(1).journal()).isEqualTo("bioRxiv");
        assertThat(result.articles().get(1).fundingStatements()).isEmpty();
    }

    @Test
    void mapsHttpErrorsAndUnreadableBodies() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));
        HttpGristClient client = new HttpGristClient(builder.build(), mapper, "https://example.org/GristAPI");

        assertThatThrownBy(() -> client.search("aff:\"X\""))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(UpstreamException.Kind.HTTP_ERROR);
                    assertThat(e.upstreamStatus()).isEqualTo(503);
                });
        assertThatThrownBy(() -> client.search("aff:\"X\""))
                .isInstanceOfSatisfying(UpstreamException.class,
                        e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.BAD_RESPONSE));
    }
}
