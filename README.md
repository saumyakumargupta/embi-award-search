# Europe PMC Institution Funding Portfolio

A small Spring Boot REST API. Given a university name, it finds grants in the Europe PMC Grants API
(Grist) whose award-holder affiliation matches, then looks up publications linked to each grant in
the Europe PMC Articles API. The caller never needs grant IDs or Grist query syntax.

Java 21, Spring Boot 3.4, Maven. No database, no UI.

## Build, run and test

```bash
# Docker
docker build -t institution-portfolio .
docker run --rm -p 8080:8080 institution-portfolio

# Or locally (JDK 21; the Maven wrapper downloads Maven)
./mvnw test                  # tests use fakes and a mock HTTP server, never live Europe PMC
./mvnw spring-boot:run
```

The Docker build runs the test suite in its build stage, so a failing test fails the image build.

### Demo mode (when the Grants API is unavailable)

For about a week during development the Europe PMC Grants API was unreachable while the Articles API
worked. It is back, and the live mode has since been tested against it (see "Institution matching").
So that the service can still be demonstrated end to end during an outage, it keeps an opt-in demo mode:

```bash
docker run --rm -p 8080:8080 -e PORTFOLIO_GRIST_MODE=demo institution-portfolio
# or: PORTFOLIO_GRIST_MODE=demo ./mvnw spring-boot:run
```

In demo mode only the Grants API step is replaced. Grants come from
`src/main/resources/demo/grist-demo-records.json`, a fixture written in Grist's own JSON shape and
read by the same parser as live responses. Publication lookups, linking, concurrency and failure
handling all run live against the Articles API.

The fixture covers Cambridge, Manchester, UCL, Edinburgh and Oxford. Its grant IDs and funders are
real (taken from funding statements on Articles API papers with authors at those universities), but
the institution assignment is illustrative, and titles, PIs and dates are left out rather than invented.
Demo responses are clearly marked with `grantSearch.source: "DEMO_FIXTURE"` and a note, and a warning
is logged at startup. The default mode is `live`.

### Saved Articles API data (when the Articles API is unavailable)

The Articles API has also had outages (it returned HTTP 503 for every query on 14 November 2026).
`src/main/resources/demo/articles-fixture.json` holds **real** Articles API results captured on
15 November 2026 for 89 grants: every grant in the demo fixture plus the first live grants for
Cambridge, Manchester, UCL, Edinburgh and Oxford. Each entry is the response to `GRANT_ID:"<id>"`
(`resultType=core`, up to 5 papers), trimmed to the fields the service reads, and it is read by the
same parser as a live response.

`portfolio.articles.mode` (environment variable `PORTFOLIO_ARTICLES_MODE`) decides when it is used:

| Mode | Behaviour |
|---|---|
| `fallback` (default) | Call the live Articles API. If a call fails (timeout, HTTP error, unreachable), use the saved result for that grant if there is one; otherwise report `LOOKUP_FAILED`. A successful live answer is always used, even an empty one. |
| `live` | Never use saved data. |
| `demo` | Always use saved data; grants with no saved entry are `LOOKUP_FAILED`. |

Every grant says where its papers came from in `publicationLookup.source`: `EUROPE_PMC_ARTICLES_API`
or `SAVED_FIXTURE`. When any grant used saved data, a `SAVED DATA` note lists those grants. A grant
missing from the file is never reported as "no publications", because a missing entry says nothing
about the grant.

```bash
# both Europe PMC APIs replaced by saved data:
docker run --rm -p 8080:8080 -e PORTFOLIO_GRIST_MODE=demo -e PORTFOLIO_ARTICLES_MODE=demo institution-portfolio
# strictly live, no saved data:
docker run --rm -p 8080:8080 -e PORTFOLIO_ARTICLES_MODE=live institution-portfolio
```

## Search page

The brief doesn't require a user interface, and the JSON API is the main deliverable. For demos there
is also a small search page, served by the same application at <http://localhost:8080/>. It is one
static file (`src/main/resources/static/index.html`) with plain HTML and JavaScript calling the two
endpoints below. There is no build step and nothing is loaded from the internet.

* Search by **university name** or by **grant number**, choosing how many grants and papers to show.
* Each grant shows its funder, institution, collaborators, a coloured lookup status, and its papers
  with links to Europe PMC and the DOI, and whether each link is confirmed or uncertain (and why).
* Demo grants and saved paper data are labelled, and 400, 502 and 504 errors are explained in words.
* The search goes into the address bar, so `/?institution=University%20of%20Leeds` or
  `/?grantId=098051` can be bookmarked or shared. "View the raw JSON" opens the API response.

## Example requests

```bash
curl "http://localhost:8080/institution-portfolio?institution=University%20of%20Cambridge&limit=5&publicationsPerGrant=3"
curl "http://localhost:8080/institution-portfolio?institution=University%20of%20Manchester"
curl "http://localhost:8080/institution-portfolio?institution=University%20College%20London&limit=10&publicationsPerGrant=5"
curl "http://localhost:8080/institution-portfolio?institution=University%20of%20Edinburgh&limit=3&publicationsPerGrant=1"
curl "http://localhost:8080/grant-portfolio?grantId=MR%2FL011840%2F1&publicationsPerGrant=3"
curl "http://localhost:8080/grant-portfolio?grantId=098051"
curl "http://localhost:8080/actuator/health"
```

## API

`GET /institution-portfolio`

| Parameter | Required | Default | Range | Meaning |
|---|---|---|---|---|
| `institution` | yes | | 2 to 200 characters | Plain institution name |
| `limit` | no | 5 | 1 to 10 | Maximum grants returned |
| `publicationsPerGrant` | no | 3 | 1 to 5 | Maximum publications per grant |

The defaults match the example in the brief and keep a default request to at most five Articles API
calls.

| Status | When |
|---|---|
| 200 | Success, including "no grants matched" and including individual publication lookups that failed |
| 400 | Missing, non-numeric or out-of-range parameter, or a name with nothing searchable |
| 502 | The Grants API failed (unreachable, HTTP error, unreadable body) |
| 504 | The Grants API timed out |

### Search by grant number

`GET /grant-portfolio?grantId=<id>&publicationsPerGrant=<1-5>` returns the same JSON shape for one
grant number. The ID is a query parameter rather than part of the path because grant IDs often contain
slashes (`MR/L011840/1`); encode them as `%2F`.

A grant number alone isn't enough to confirm papers, because numbers are reused across funders. So the
service first searches the Grants API with its grant-ID field, `gid:"<id>"`, to find the grant and its
funder, then runs the same paper lookups and funder check as a university search. `gid:` is a word
search, so only grants whose ID is exactly the one asked for are kept; if there are none, a note lists
similar IDs that were found. If several funders use the number, each is shown as a separate grant.
`request.grantId` replaces `request.institution`, and `institution.nameMatch` is omitted.

Errors use RFC 9457 `application/problem+json`, with `parameter` for 400s and `upstreamService` /
`failure` for 502/504.

### Response shape (abridged)

```jsonc
{
  "request":     { "institution": "...", "normalisedInstitution": "...", "grantsQuery": "aff:\"...\"",
                   "limit": 5, "publicationsPerGrant": 3 },
  "grantSearch": { "source": "EUROPE_PMC_GRANTS_API", "totalHitCount": 3265,   // Grist records, not grants
                   "pagesFetched": 2, "recordsFetched": 50, "distinctGrantsFound": 23,
                   "returned": 5, "truncated": true },
  "grants": [{
    "rank": 1,                                   // Grist order, after merging per-person records
    "grantId": "340947",
    "title": "...",
    "funder":      { "name": "Wellcome Trust", "fundRefId": "https://doi.org/10.13039/100010269" },
    "institution": { "name": "University of Manchester", "rorId": "027m9bs27", "nameMatch": "EXACT" },
    "allInstitutions": [ "University of Illinois Chicago", "University of Dundee", "...", "University of Manchester" ],
    "principalInvestigator": "...", "startDate": "2026-09-01", "endDate": "2032-09-01",
    "publicationLookup": { "status": "PUBLICATIONS_FOUND", "source": "EUROPE_PMC_ARTICLES_API",
                           "query": "GRANT_ID:\"340947\"", "totalHitCount": 1 },
    "publications": [{
      "key": "MED:42720975",                     // stable identity: source + id
      "source": "MED", "id": "42720975", "pmid": "...", "pmcid": "...", "doi": "10.7554/elife.111066",
      "title": "...", "journal": "eLife", "pubYear": "2026",
      "association": "CONFIRMED", "matchedFunderAgency": "Wellcome Trust"
    }]
  }],
  "summary": {
    "scope": "Counts describe only the grants and publications returned in this response, ...",
    "grantsReturned": 5,
    "grantsByFunder": { "Wellcome Trust": 3, "Medical Research Council": 2 },
    "grantsByLookupStatus": { "PUBLICATIONS_FOUND": 3, "NO_PUBLICATIONS_FOUND": 1, "LOOKUP_FAILED": 1 },
    "publicationAssociations": 10, "confirmedAssociations": 8, "uncertainAssociations": 2,
    "uniquePublications": 9
  },
  "notes": [ "... this is a sample, not the institution's full funding portfolio ..." ]
}
```

Null fields are omitted rather than sent as `null`.

## Design

```
PortfolioController  ->  PortfolioService  ->  GristClient    (HttpGristClient)
   (validation,            (orchestration,       ArticlesClient (HttpArticlesClient)
    error mapping)          concurrency,
                            linking, summary)
```

* The two Europe PMC clients are interfaces. HTTP details, encoding and error mapping live in the
  `Http*Client` classes; the service only sees domain records and `UpstreamException`. This is what
  lets the service tests replace Europe PMC with simple lambdas.
* Upstream JSON is read as a `JsonNode` tree by small parsers rather than mapped onto rigid DTOs.
  Every field is optional, a repeated element may be a single object instead of an array, and text
  may arrive wrapped as `{"value": "..."}` (as live Grist abstracts and amounts do). Missing data
  becomes `null`, never an exception.
* The Articles API is called with `resultType=core` because only the core result includes each
  article's `grantsList`, which is needed to check the funder (see below). The page size equals
  `publicationsPerGrant`, so payloads stay small.

### Institution matching

1. The name is NFC-normalised, control characters and whitespace runs are collapsed, and `"` and `\`
   are replaced with spaces. This means user input cannot close the phrase and inject other Grist
   clauses (for example `Oxford" OR ga:"x`).
2. It is sent as one phrase on the documented affiliation field: `aff:"University of Manchester"`.
3. Grist takes the query in the URL path (`/rest/get/query=...&format=json`), so the query is
   percent-encoded by hand, including `&` as `%26`, so `Texas A&M University` is not split into
   parameters. The URI is passed to the HTTP client pre-built so it is not encoded twice.
4. **Grist returns one record per person on a grant, not one per grant.** Testing against the live API
   showed that a grant with collaborators elsewhere appears once for each person: grant `352259`
   appears 8 times on the first `aff:"University of Manchester"` page, once for the Manchester PI and
   once each for collaborators in Bristol, Liverpool and Malawi, and the Bristol record comes first.
   The first 25-record page held 10 distinct grants for Manchester and only 5 for Cambridge.
   `GrantGrouper` therefore merges records with the same funder and grant ID, keeps the order in which
   each grant first appears (Grist order), represents each grant by the record whose institution best
   matches the request, and lists every institution seen in `allInstitutions`.
5. If a page does not yield more than `limit` distinct grants, the next page is read, up to
   `portfolio.grist.max-pages` (default 3, i.e. 75 records). Reading one page past `limit` means the
   last grant shown has all its records and `truncated` is accurate. The brief says one page
   suffices; with one record per person, one page often cannot supply 10 grants, so this is a
   deliberate extension. A failure on page 1 fails the request; a failure on a later page keeps what
   was found and says so in `notes`.
6. Each grant reports `institution.nameMatch` for its representative record: `EXACT` (same name,
   ignoring case), `PARTIAL` (one contains the other, e.g. `aff:"Cambridge"` and "University of
   Cambridge"), `OTHER` (even the best record names a different institution) or `UNKNOWN`.
   `institution.rorId` carries the ROR ID, normalised because Grist writes it both as `013meh722`
   and as `https://ror.org/013meh722`.

Limitations: no synonym or variant expansion ("UCL" and "University College London" are different
searches), the request is not resolved to a ROR ID before searching, and Grist only indexes awards
from its participating funders. Grist lists the most recent awards first (on 14 November 2026 the
first Manchester grants started between June 2026 and January 2027), so many returned grants are too
new to have papers yet; `NO_PUBLICATIONS_FOUND` is common for that reason.

### Grant to publication linking

For each grant with an ID, the service searches the Articles API with `GRANT_ID:"<id>"`. A hit only
proves that some funding statement on the article mentions that ID. IDs are not unique across funders
(short numeric IDs especially), so each returned article is classified against its own `grantsList`:

| `association` | Meaning |
|---|---|
| `CONFIRMED` | The article lists the same grant ID **and** an agency matching the Grist funder |
| `UNCERTAIN_FUNDER_UNKNOWN` | Same ID, but the article (or Grist) gives no funder for it |
| `UNCERTAIN_FUNDER_MISMATCH` | Same ID attributed to a different funder |
| `UNCERTAIN_NO_EXACT_GRANT_ID` | The search matched but no statement carries exactly this ID |

ID comparison ignores case and whitespace and strips a DOI prefix, so Wellcome's `10.35802/098051`
matches `098051`. Funder comparison ignores case and punctuation and accepts containment
("Wellcome" vs "Wellcome Trust"). It does not resolve acronyms or funder hierarchies.

Uncertain matches are **returned and labelled**, never silently dropped or promoted to verified.
The grant-level `publicationLookup.status` then summarises:

| `status` | Meaning |
|---|---|
| `PUBLICATIONS_FOUND` | At least one returned publication is `CONFIRMED` |
| `UNCERTAIN_ASSOCIATION` | Publications returned, none confirmed |
| `NO_PUBLICATIONS_FOUND` | Lookup succeeded with zero hits. Not proof that none exist |
| `LOOKUP_FAILED` | Lookup failed or timed out; `error` says why. Absence is unknown |
| `NOT_SEARCHED_NO_GRANT_ID` | The Grist record had no ID to search for |

**Deduplication.** Within one grant, a repeated `source:id` is dropped. Across grants nothing is
deduplicated, because one paper legitimately acknowledging two returned grants is two real
associations. The summary reports both `publicationAssociations` and `uniquePublications`.

**Identity.** `key` is Europe PMC `source:id` (for example `MED:12345`, `PPR:PPR1331636`), which
is stable for preprints and records without a PMID. `doi`, `pmid` and `pmcid` are also included
where present.

## Concurrency and failure handling

* After the Grist pages are read and merged, one task per distinct grant is submitted to a virtual-thread executor, so all
  lookups for a request start together and overlap.
* A fair `Semaphore` owned by the service caps Articles API calls in flight at **4**
  (`portfolio.articles.max-concurrent`). Because it is shared by all incoming requests, ten users
  asking at once still produce at most four concurrent calls to Europe PMC. Virtual threads make
  waiting for a permit cheap; the semaphore, not the thread pool, is the actual limit.
* Results are gathered by index, so each grant's publications appear under the right grant in Grist
  order regardless of completion order.
* Timeouts: connect 3 s; read 15 s for Grist and 8 s for the Articles API; and an overall
  **20 s deadline** for all of one request's lookups, including time spent queued for a permit. A
  lookup still running at the deadline is cancelled and reported as `LOOKUP_FAILED`.
* Each lookup catches its own `UpstreamException`, so one failure marks one grant and the rest of the
  response is unaffected. Only a Grist failure fails the whole request (502/504), because without
  grants there is nothing to return.
* No retries against the live API. With a 20 s budget and a shared API, a retry storm is a worse
  failure mode than one clearly reported failed lookup. In `fallback` mode a failed lookup is answered
  from saved data instead, which costs Europe PMC nothing (see "Saved Articles API data").
* Spring's JDK request factory enforces its read timeout by cancelling the request, which can surface
  as a bare `CancellationException`. Seen live; it is mapped to a timeout like any other.
* The HTTP client uses HTTP/1.1 and the JVM's default `ProxySelector`, so it works behind a
  corporate proxy configured with `-Dhttps.proxyHost`.

All tunables are in `application.yml` and can be overridden with environment variables, for example
`docker run -e PORTFOLIO_ARTICLES_MAX_CONCURRENT=2 ...` or `-e PORTFOLIO_GRIST_MAX_PAGES=1`.

## Tests

`./mvnw test` runs 61 tests with no network access:

| Test class | What it covers |
|---|---|
| `QueryBuilderTest` | Phrase construction for names and grant IDs, whitespace cleaning, phrase-injection, accents and `&`, rejected input |
| `HttpClientsTest` | Exact encoded Grist path and Articles URL; parsing; 503, HTML-body and cancelled-request error mapping (MockRestServiceServer) |
| `GristResponseParserTest` | Normal records, single record as an object, missing fields, empty result, and the live format (`value` wrappers, both ROR ID spellings) |
| `GrantGrouperTest` | Per-person records merged into one grant in first-appearance order, best-matching record chosen, same ID from different funders kept apart, `nameMatch` values |
| `PublicationLinkerTest` | Confirmed, funder mismatch, unknown funder, no exact ID, DOI-form IDs |
| `PortfolioServiceTest` | Order preserved under reversed completion; failure and timeout isolation; failed vs none-found; concurrency never exceeds the limit but does overlap; per-grant dedup with cross-grant duplicates kept; grants with no ID; limits; reading more pages until enough distinct grants, the page cap, and failure on a later page vs the first; grant-number search keeping exact IDs only, one grant per funder, and a note with near misses |
| `SavedArticlesTest` | Saved data answers like the live API but flagged and capped at page size; a grant with no saved data is a failure, not "none found"; fallback uses live whenever live answers (even empty), saved data only when live fails, and reports the live failure when nothing is saved |
| `DemoGristClientTest` | Demo fixture filtering by affiliation, case-insensitive partial match, unknown institution |
| `PortfolioControllerTest` | Full Spring wiring: JSON shape, defaults, 400s, 502 and 504; the grant-number endpoint; the search page is served |

## Limitations

* At most `max-pages` Grist pages (75 records by default) are read, so a grant list dominated by large
  collaborations can still return fewer than `limit` grants; a note says so. `totalHitCount` is
  Grist's record count, not a grant count.
* Grist lists the newest awards first, so many returned grants have no papers yet.
* Summary counts describe only the returned sample, and say so in the response.
* Funder matching is string-based. An MRC grant acknowledged as "UK Research and Innovation" (MRC's
  parent body) is reported as `UNCERTAIN_FUNDER_MISMATCH` even though it is probably correct. The
  check also earns its keep: live, Wellcome grant `340947` at Manchester shares its number with a
  Canadian Institutes of Health Research grant, and the paper found belongs to the Canadian one; it
  is correctly reported as `UNCERTAIN_FUNDER_MISMATCH` rather than confirmed.
* Publications are taken in Articles API relevance order, so uncertain matches can take slots that
  a confirmed match lower down would have filled.
* No caching. Repeated requests for the same university repeat every upstream call.
* In a grant-number search there is no requested institution to prefer, so the grant is shown with the
  institution on its first Grants API record, which may be a collaborator's. `allInstitutions` lists
  them all.
* The search page is a convenience for demos. It has no automated browser tests; it was checked by
  hand in Chromium at desktop and phone widths, in light and dark mode.
* Saved Articles data goes out of date and covers only 89 grants. It is labelled on every grant that
  uses it, and `fallback` only uses it when the live call has already failed.

## Review of my solution

**Which design decision worked particularly well?**

Making every paper lookup come back with an answer instead of throwing. `lookUp()` catches its own
errors and returns an entry marked `LOOKUP_FAILED` with the reason, and the results are read back by
their position in the list rather than in the order they finish.

I didn't fully appreciate how much that bought me until Europe PMC started failing during testing. On
14 November the Articles API returned 503 for every query, and the service still answered each
request with a 200, every grant saying exactly what had gone wrong. When I later added the fallback to
saved data, it slotted in as one more `ArticlesClient` wrapped around the live one, and
`PortfolioService` didn't need to change at all. It also kept the tests simple: the fake clients are
one-line lambdas, so the concurrency and failure tests run without a mock HTTP server.

**What would I change or improve with more time?**

The matching, on both sides. For institutions, I'd resolve the name to a ROR ID before searching.
Grist already puts a ROR ID on each record, so I could keep only the rows for the right organisation
instead of trusting a phrase search on `aff:`, which also picks up departments and collaborators. For
funders, I'd compare FundRef IDs instead of names. At the moment an MRC grant that a paper credits to
"UK Research and Innovation" comes out as uncertain, and writing "&" instead of "and" is enough to
break a match.

I'd also replace the saved Articles file with a proper cache that refreshes itself, because the file is
a snapshot from 15 November and will drift. And I'd add a small scheduled test against the live APIs.
The biggest surprises in this project came from the real data, not from the code.

**Which assumption about Europe PMC data would I validate before production?**

One of my assumptions has already turned out to be wrong. The Grants API was down for most of the week
I built this, so I wrote the parser from the documentation and took each record to be a grant. When the
API came back, each record turned out to be one person on a grant. Grant `352259` appeared eight times
on the first Manchester page, and the first of those rows named a collaborator in Bristol. Merging rows
by funder and grant ID fixed it, but I'd want to check this on many more institutions before relying on
it: whether a grant's rows always sit next to each other, for example, and how `HitCount` relates to
the number of distinct grants.

The next assumption I'd test is that funder names in the two APIs line up well enough for my check to
work. That check decides whether a link is confirmed, and so far I've only looked at it by hand. I'd
measure what share of links come out confirmed for each funder across a few hundred grants.

**Which piece of technical debt did I deliberately accept?**

The funder comparison. It lower-cases both names, strips the punctuation, and counts a match if one
name contains the other. It's crude, and I chose it knowingly: a proper funder identity map didn't fit
in three hours, and I'd rather have something anyone can read and reason about. What makes it
acceptable is the direction it fails in. When it's wrong, a real link gets flagged as uncertain, rather
than a false one being confirmed.

It also earned its place during testing. Wellcome grant `340947` at Manchester shares its number with a
Canadian (CIHR) grant, and the paper the Articles API returned belonged to the Canadian one. The check
marked it as a funder mismatch instead of confirming it.

The saved Articles file is a smaller piece of debt of the same kind. It's labelled clearly wherever
it's used, but it's a snapshot, not a cache.
