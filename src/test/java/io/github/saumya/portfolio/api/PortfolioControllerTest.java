package io.github.saumya.portfolio.api;

import io.github.saumya.portfolio.client.ArticlesClient;
import io.github.saumya.portfolio.client.GristClient;
import io.github.saumya.portfolio.client.UpstreamException;
import io.github.saumya.portfolio.domain.Article;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full Spring wiring with the two Europe PMC clients replaced by mocks. */
@SpringBootTest
@AutoConfigureMockMvc
class PortfolioControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GristClient grist;

    @MockitoBean
    ArticlesClient articles;

    @Test
    void returnsPortfolioJson() throws Exception {
        when(grist.search("aff:\"University of Manchester\"")).thenReturn(new GrantSearchResult(57L, List.of(
                new Grant("MR/1", "A grant", "Medical Research Council", null, "University of Manchester",
                        null, "Dr A Person", "2019-01-01", "2023-01-01"))));
        when(articles.search(eq("GRANT_ID:\"MR/1\""), anyInt())).thenReturn(new ArticleSearchResult(9, List.of(
                new Article("MED", "111", "111", "PMC1", "10.1/abc", "A paper", "Nature", "2021",
                        List.of(new Article.FundingStatement("Medical Research Council", "MR/1"))))));

        mvc.perform(get("/institution-portfolio").param("institution", "University of Manchester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.grantsQuery").value("aff:\"University of Manchester\""))
                .andExpect(jsonPath("$.request.limit").value(5))
                .andExpect(jsonPath("$.request.publicationsPerGrant").value(3))
                .andExpect(jsonPath("$.grantSearch.source").value("EUROPE_PMC_GRANTS_API"))
                .andExpect(jsonPath("$.grantSearch.totalHitCount").value(57))
                .andExpect(jsonPath("$.grantSearch.truncated").value(true))
                .andExpect(jsonPath("$.grants[0].funder.name").value("Medical Research Council"))
                .andExpect(jsonPath("$.grants[0].institution.nameMatch").value("EXACT"))
                .andExpect(jsonPath("$.grants[0].publicationLookup.status").value("PUBLICATIONS_FOUND"))
                .andExpect(jsonPath("$.grants[0].publications[0].key").value("MED:111"))
                .andExpect(jsonPath("$.grants[0].publications[0].doi").value("10.1/abc"))
                .andExpect(jsonPath("$.grants[0].publications[0].association").value("CONFIRMED"))
                .andExpect(jsonPath("$.summary.grantsByFunder['Medical Research Council']").value(1));
    }

    @Test
    void invalidInputIs400() throws Exception {
        mvc.perform(get("/institution-portfolio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("institution"));
        mvc.perform(get("/institution-portfolio").param("institution", "Leeds").param("limit", "11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("limit"));
        mvc.perform(get("/institution-portfolio").param("institution", "Leeds").param("publicationsPerGrant", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.parameter").value("publicationsPerGrant"));
    }

    @Test
    void grantsApiFailureIs502AndTimeoutIs504() throws Exception {
        when(grist.search(anyString()))
                .thenThrow(new UpstreamException("Europe PMC Grants API", UpstreamException.Kind.UNAVAILABLE,
                        null, "Europe PMC Grants API could not be reached", null))
                .thenThrow(new UpstreamException("Europe PMC Grants API", UpstreamException.Kind.TIMEOUT,
                        null, "Europe PMC Grants API did not respond in time", null));

        mvc.perform(get("/institution-portfolio").param("institution", "Leeds"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.upstreamService").value("Europe PMC Grants API"));
        mvc.perform(get("/institution-portfolio").param("institution", "Leeds"))
                .andExpect(status().isGatewayTimeout());
    }
}
