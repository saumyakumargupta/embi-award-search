package io.github.saumya.portfolio.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Builds Europe PMC query strings from user input.
 *
 * <p>The user supplies a plain institution name; they never write Grist syntax. The name is cleaned and
 * wrapped in a phrase query on the documented affiliation field, e.g. {@code aff:"University of Leeds"}.
 * Characters that would end or escape the phrase ({@code "} and {@code \}) are replaced with spaces, so
 * the input cannot inject extra query clauses. URL encoding is a separate concern handled by the HTTP
 * clients.
 */
public final class QueryBuilder {

    public static final int MAX_INSTITUTION_LENGTH = 200;

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
    private static final Pattern PHRASE_BREAKERS = Pattern.compile("[\"\\\\]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern HAS_LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

    private QueryBuilder() {
    }

    /** Normalises a user-supplied institution name, or throws {@link InvalidRequestException}. */
    public static String normaliseInstitution(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("institution", "institution is required");
        }
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC);
        name = CONTROL_CHARS.matcher(name).replaceAll(" ");
        name = PHRASE_BREAKERS.matcher(name).replaceAll(" ");
        name = WHITESPACE.matcher(name).replaceAll(" ").trim();

        if (name.length() > MAX_INSTITUTION_LENGTH) {
            throw new InvalidRequestException("institution",
                    "institution must be at most " + MAX_INSTITUTION_LENGTH + " characters");
        }
        if (name.length() < 2 || !HAS_LETTER_OR_DIGIT.matcher(name).find()) {
            throw new InvalidRequestException("institution",
                    "institution must contain at least two characters including a letter or digit");
        }
        return name;
    }

    public static final int MAX_GRANT_ID_LENGTH = 100;

    /**
     * Cleans a user-supplied grant number the same way as a name: no control characters, no quote marks
     * or backslashes that could break out of the phrase, single spaces. Grant IDs are short, so the
     * limit is 100 characters, and at least one letter or digit is required.
     */
    public static String normaliseGrantId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("grantId", "grantId is required");
        }
        String id = Normalizer.normalize(raw, Normalizer.Form.NFC);
        id = CONTROL_CHARS.matcher(id).replaceAll(" ");
        id = PHRASE_BREAKERS.matcher(id).replaceAll(" ");
        id = WHITESPACE.matcher(id).replaceAll(" ").trim();
        if (id.length() > MAX_GRANT_ID_LENGTH) {
            throw new InvalidRequestException("grantId",
                    "grantId must be at most " + MAX_GRANT_ID_LENGTH + " characters");
        }
        if (!HAS_LETTER_OR_DIGIT.matcher(id).find()) {
            throw new InvalidRequestException("grantId", "grantId must contain a letter or digit");
        }
        return id;
    }

    /** {@code gid:"<id>"}: Grist's documented grant-ID field, for an already-normalised ID. */
    public static String gristGrantIdQuery(String normalisedGrantId) {
        return "gid:\"" + normalisedGrantId + "\"";
    }

    /** {@code aff:"<name>"} for an already-normalised name. */
    public static String gristAffiliationQuery(String normalisedName) {
        return "aff:\"" + normalisedName + "\"";
    }

    /** {@code GRANT_ID:"<id>"} for the Articles REST API, with phrase-breaking characters escaped. */
    public static String articlesGrantIdQuery(String grantId) {
        String escaped = grantId.trim().replace("\\", "\\\\").replace("\"", "\\\"");
        return "GRANT_ID:\"" + escaped + "\"";
    }
}
