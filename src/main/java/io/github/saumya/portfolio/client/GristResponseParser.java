package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.saumya.portfolio.domain.Grant;
import io.github.saumya.portfolio.domain.GrantSearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a Grist {@code format=json&resultType=core} response into {@link Grant} records.
 *
 * <p>Expected shape (abridged):
 * <pre>
 * { "HitCount": 123,
 *   "RecordList": { "Record": [
 *     { "Person":      { "FamilyName": "...", "GivenName": "...", "Initials": "..." },
 *       "Grant":       { "Id": "...", "Title": "...", "Funder": { "Name": "...", "FundRefID": "..." },
 *                        "StartDate": "...", "EndDate": "..." },
 *       "Institution": { "Name": "...", "Department": "...", "RORID": "..." } } ] } }
 * </pre>
 * Every element is treated as optional, {@code Record} may be a single object instead of an array,
 * and an absent {@code RecordList} is read as "no grants".
 */
public final class GristResponseParser {

    private GristResponseParser() {
    }

    public static GrantSearchResult parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Grist response is not a JSON object");
        }
        Long hitCount = parseLong(Json.text(root, "HitCount"));

        List<Grant> grants = new ArrayList<>();
        for (JsonNode record : Json.list(Json.path(root, "RecordList", "Record"))) {
            grants.add(toGrant(record));
        }
        return new GrantSearchResult(hitCount, grants);
    }

    private static Grant toGrant(JsonNode record) {
        JsonNode grant = Json.child(record, "Grant");
        JsonNode funder = Json.child(grant, "Funder");
        JsonNode institution = Json.child(record, "Institution");

        // Funder is normally an object with a Name, but tolerate a bare string.
        String funderName = funder != null && funder.isObject()
                ? Json.text(funder, "Name")
                : Json.text(funder);

        return new Grant(
                Json.text(grant, "Id"),
                Json.text(grant, "Title"),
                funderName,
                Json.text(funder, "FundRefID"),
                Json.text(institution, "Name"),
                Json.text(institution, "Department"),
                personName(Json.child(record, "Person")),
                Json.text(grant, "StartDate"),
                Json.text(grant, "EndDate"),
                rorId(Json.text(institution, "RORID")));
    }

    /**
     * Grist writes ROR IDs both as {@code 027m9bs27} and as {@code https://ror.org/027m9bs27};
     * keep only the ID so the same institution always compares equal.
     */
    static String rorId(String raw) {
        if (raw == null) {
            return null;
        }
        int slash = raw.lastIndexOf('/');
        String id = slash >= 0 ? raw.substring(slash + 1) : raw;
        return id.isBlank() ? null : id;
    }

    private static String personName(JsonNode person) {
        if (person == null) {
            return null;
        }
        String family = Json.text(person, "FamilyName");
        String given = Json.text(person, "GivenName");
        if (given == null) {
            given = Json.text(person, "Initials");
        }
        String title = Json.text(person, "Title");
        String name = String.join(" ", nonNull(title, given, family)).trim();
        return name.isEmpty() ? null : name;
    }

    private static List<String> nonNull(String... parts) {
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part != null) {
                out.add(part);
            }
        }
        return out;
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
