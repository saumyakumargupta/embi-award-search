package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.saumya.portfolio.domain.ArticleSearchResult;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers Articles API searches from saved results instead of calling Europe PMC.
 *
 * <p>The bundled file holds real Articles API responses captured for a set of grants (the demo grants
 * and the first live grants for five universities), trimmed to the fields the service reads. Entries are
 * keyed by grant ID. A saved response is passed through the real {@link ArticlesResponseParser}, so
 * everything after this point behaves exactly as for a live result, except that the result is flagged
 * {@code fromFixture} and the response says so. A grant with no saved entry is reported as a failure,
 * never as "no publications", because the absence of saved data says nothing about the grant.
 */
public class FixtureArticlesClient implements ArticlesClient {

    public static final String DEFAULT_FIXTURE = "demo/articles-fixture.json";
    static final String SERVICE = "Saved Articles API data";

    private static final Pattern GRANT_ID_QUERY = Pattern.compile("^GRANT_ID:\"(.*)\"$");

    private final ObjectMapper mapper;
    private final Map<String, JsonNode> lookups = new HashMap<>();
    private final String capturedOn;

    public FixtureArticlesClient(ObjectMapper mapper, String classpathFixture) {
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource(classpathFixture).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            this.capturedOn = Json.text(root, "capturedOn");
            Iterator<Map.Entry<String, JsonNode>> it = root.path("lookups").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                lookups.put(key(e.getKey()), e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Articles fixture " + classpathFixture, e);
        }
    }

    @Override
    public ArticleSearchResult search(String query, int pageSize) {
        String grantId = grantIdFrom(query);
        JsonNode saved = grantId == null ? null : lookups.get(key(grantId));
        if (saved == null) {
            throw new UpstreamException(SERVICE, UpstreamException.Kind.UNAVAILABLE, null,
                    "no saved Articles API data for this grant", null);
        }

        // Return at most pageSize saved results, like the live API would.
        ArrayNode page = mapper.createArrayNode();
        List<JsonNode> results = Json.list(Json.path(saved, "resultList", "result"));
        for (int i = 0; i < Math.min(pageSize, results.size()); i++) {
            page.add(results.get(i));
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("hitCount", saved.path("hitCount").asLong(0));
        response.putObject("resultList").set("result", page);

        ArticleSearchResult parsed = ArticlesResponseParser.parse(response);
        return new ArticleSearchResult(parsed.hitCount(), parsed.articles(), true);
    }

    /** Date the saved data was captured, e.g. {@code 2026-11-15}. */
    public String capturedOn() {
        return capturedOn;
    }

    public int size() {
        return lookups.size();
    }

    /** Reverses {@code QueryBuilder.articlesGrantIdQuery}: {@code GRANT_ID:"x"} back to {@code x}. */
    static String grantIdFrom(String query) {
        Matcher m = GRANT_ID_QUERY.matcher(query.trim());
        if (!m.matches()) {
            return null;
        }
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String key(String grantId) {
        return grantId.trim().toUpperCase(Locale.ROOT);
    }
}
