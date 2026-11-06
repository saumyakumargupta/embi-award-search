package io.github.saumya.portfolio.service;

import io.github.saumya.portfolio.domain.Article;
import io.github.saumya.portfolio.domain.Grant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides how strongly an article returned by a {@code GRANT_ID:"x"} search is linked to a Grist grant.
 *
 * <p>A GRANT_ID search only proves that <em>some</em> funding statement on the article mentions an ID
 * like {@code x}. Grant IDs are not globally unique across funders (short numeric IDs especially), so
 * an association is only reported as {@link Association#CONFIRMED} when the article's own
 * {@code grantsList} contains the same ID <em>and</em> an agency that matches the Grist funder.
 * Everything else is returned but labelled as uncertain, with the reason.
 */
public final class PublicationLinker {

    /** How an article relates to the grant it was found under. Ordered from strongest to weakest. */
    public enum Association {
        /** Same grant ID and a matching funder in the article's funding metadata. */
        CONFIRMED,
        /** Same grant ID, but the article does not name a funder for it, or Grist has no funder. */
        UNCERTAIN_FUNDER_UNKNOWN,
        /** Same grant ID attributed to a different funder: possibly a different award with the same ID. */
        UNCERTAIN_FUNDER_MISMATCH,
        /** The search matched, but no funding statement carries exactly this ID (e.g. a partial token match). */
        UNCERTAIN_NO_EXACT_GRANT_ID
    }

    /** Result of classifying one article, including the funder text that produced the match. */
    public record Link(Association association, String matchedAgency) {
    }

    private static final Pattern DOI_PREFIX = Pattern.compile("^10\\.\\d{4,9}/(.+)$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");

    private PublicationLinker() {
    }

    public static Link classify(Grant grant, Article article) {
        String wantedId = normaliseId(grant.id());
        Link best = new Link(Association.UNCERTAIN_NO_EXACT_GRANT_ID, null);

        for (Article.FundingStatement statement : article.fundingStatements()) {
            if (statement.grantId() == null || !normaliseId(statement.grantId()).equals(wantedId)) {
                continue;
            }
            Association candidate;
            if (statement.agency() == null || grant.funderName() == null) {
                candidate = Association.UNCERTAIN_FUNDER_UNKNOWN;
            } else if (sameFunder(grant.funderName(), statement.agency())) {
                candidate = Association.CONFIRMED;
            } else {
                candidate = Association.UNCERTAIN_FUNDER_MISMATCH;
            }
            if (candidate.ordinal() < best.association().ordinal()) {
                best = new Link(candidate, statement.agency());
            }
        }
        return best;
    }

    /**
     * Upper-cases, removes whitespace and strips a DOI prefix, so that {@code 098051},
     * {@code 098051 } and Wellcome's DOI form {@code 10.35802/098051} compare equal.
     */
    static String normaliseId(String id) {
        String value = id.trim();
        Matcher doi = DOI_PREFIX.matcher(value);
        if (doi.matches()) {
            value = doi.group(1);
        }
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Loose funder-name comparison: ignores case and punctuation and accepts containment, so
     * "Wellcome Trust" matches "Wellcome", and "NIH/NIAID" matches "NIH NIAID". It deliberately does not
     * try to resolve acronyms (MRC vs Medical Research Council); those fall back to "uncertain".
     */
    static boolean sameFunder(String gristFunder, String articleAgency) {
        String a = NON_ALNUM.matcher(gristFunder.toLowerCase(Locale.ROOT)).replaceAll("");
        String b = NON_ALNUM.matcher(articleAgency.toLowerCase(Locale.ROOT)).replaceAll("");
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        return shorter.length() >= 4 && longer.contains(shorter);
    }
}
