package io.github.saumya.portfolio.service;

import io.github.saumya.portfolio.api.PortfolioResponse.NameMatch;
import io.github.saumya.portfolio.domain.Grant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns Grist records into distinct grants.
 *
 * <p>The live Grants API returns one record per person on a grant. A grant with a Manchester PI and
 * collaborators in Bristol and Malawi therefore arrives as several records with the same funder and ID,
 * each carrying a different institution, and an {@code aff:} search returns all of them. This class
 * merges records that share funder and grant ID, keeps the order in which each grant first appears
 * (so Grist order is preserved), and picks as the grant's representative the record whose institution
 * best matches the requested name.
 */
public final class GrantGrouper {

    /** One distinct grant, the record chosen to represent it, and every institution seen on it. */
    public record GroupedGrant(Grant representative, NameMatch nameMatch, List<String> institutions, int records) {
    }

    private GrantGrouper() {
    }

    /**
     * @param requestedName the institution the user asked for, or null when searching by grant ID. With
     *                      null, each grant is represented by its first record and has no name match.
     */
    public static List<GroupedGrant> group(List<Grant> records, String requestedName) {
        Map<String, List<Grant>> byGrant = new LinkedHashMap<>();
        int anonymous = 0;
        for (Grant record : records) {
            // A record without an ID cannot be matched to others, so it stays on its own.
            String key = record.hasId() ? key(record) : "no-id#" + anonymous++;
            byGrant.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }

        List<GroupedGrant> grants = new ArrayList<>(byGrant.size());
        for (List<Grant> group : byGrant.values()) {
            Grant best = group.get(0);
            NameMatch bestMatch = requestedName == null ? null : nameMatch(requestedName, best.institutionName());
            Set<String> institutions = new LinkedHashSet<>();
            for (Grant record : group) {
                if (record.institutionName() != null) {
                    institutions.add(record.institutionName());
                }
                if (requestedName == null) {
                    continue;
                }
                NameMatch match = nameMatch(requestedName, record.institutionName());
                if (match.ordinal() < bestMatch.ordinal()) {
                    best = record;
                    bestMatch = match;
                }
            }
            grants.add(new GroupedGrant(best, bestMatch, List.copyOf(institutions), group.size()));
        }
        return grants;
    }

    /** Same grant = same funder and same grant ID, ignoring case and surrounding spaces. */
    static String key(Grant record) {
        String funder = record.funderName() == null ? "" : record.funderName().trim().toLowerCase(Locale.ROOT);
        return funder + "|" + record.id().trim().toUpperCase(Locale.ROOT);
    }

    /**
     * How the institution on a record relates to what the user typed:
     * EXACT (same name), PARTIAL (one contains the other, e.g. "Cambridge" and "University of Cambridge"),
     * OTHER (a different institution, typically a collaborator's) or UNKNOWN (no institution given).
     */
    public static NameMatch nameMatch(String requested, String reported) {
        if (reported == null || reported.isBlank()) {
            return NameMatch.UNKNOWN;
        }
        String a = requested.trim().toLowerCase(Locale.ROOT);
        String b = reported.trim().toLowerCase(Locale.ROOT);
        if (a.equals(b)) {
            return NameMatch.EXACT;
        }
        if (a.contains(b) || b.contains(a)) {
            return NameMatch.PARTIAL;
        }
        return NameMatch.OTHER;
    }
}
