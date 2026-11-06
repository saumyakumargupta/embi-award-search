package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.saumya.portfolio.domain.GrantSearchResult;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for the Grants API, used only when {@code portfolio.grist.mode=demo}.
 *
 * <p>Reads a bundled fixture written in Grist's own JSON shape, keeps the records whose institution
 * name or department contains the requested {@code aff:} phrase (case-insensitive, roughly what
 * Grist's affiliation search does), and passes the result through the real {@link GristResponseParser}.
 * Everything after this point, including the Articles API lookups, runs exactly as in live mode.
 */
public class DemoGristClient implements GristClient {

    public static final String DEFAULT_FIXTURE = "demo/grist-demo-records.json";

    private static final Pattern AFF_PHRASE = Pattern.compile("^aff:\"(.*)\"$");

    private final ObjectMapper mapper;
    private final JsonNode records;

    public DemoGristClient(ObjectMapper mapper, String classpathFixture) {
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource(classpathFixture).getInputStream()) {
            this.records = Json.path(mapper.readTree(in), "RecordList", "Record");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read demo fixture " + classpathFixture, e);
        }
    }

    @Override
    public GrantSearchResult search(String gristQuery) {
        Matcher m = AFF_PHRASE.matcher(gristQuery);
        String phrase = (m.matches() ? m.group(1) : gristQuery).toLowerCase(Locale.ROOT);

        ArrayNode matched = mapper.createArrayNode();
        for (JsonNode record : Json.list(records)) {
            JsonNode institution = Json.child(record, "Institution");
            if (contains(Json.text(institution, "Name"), phrase)
                    || contains(Json.text(institution, "Department"), phrase)) {
                matched.add(record);
            }
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("HitCount", matched.size());
        response.putObject("RecordList").set("Record", matched);
        return GristResponseParser.parse(response);
    }

    @Override
    public boolean isDemo() {
        return true;
    }

    private static boolean contains(String value, String phrase) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(phrase);
    }
}
