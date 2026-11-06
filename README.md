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

During development the Europe PMC Grants API was unreachable while the Articles API worked. So that
the service can still be demonstrated end to end, it has an opt-in demo mode:

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

## Example requests

```bash
curl "http://localhost:8080/institution-portfolio?institution=University%20of%20Cambridge&limit=5&publicationsPerGrant=3"
curl "http://localhost:8080/institution-portfolio?institution=University%20of%20Manchester"
curl "http://localhost:8080/institution-portfolio?institution=University%20College%20London&limit=10&publicationsPerGrant=5"
curl "http://localhost:8080/institution-portfolio?institution=University%20of%20Edinburgh&limit=3&publicationsPerGrant=1"
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

Errors use RFC 9457 `application/problem+json`, with `parameter` for 400s and `upstreamService` /
`failure` for 502/504.

### Response shape (abridged)

```jsonc
{
  "request":     { "institution": "...", "normalisedInstitution": "...", "grantsQuery": "aff:\"...\"",
                   "limit": 5, "publicationsPerGrant": 3 },
  "grantSearch": { "source": "EUROPE_PMC_GRANTS_API", "totalHitCount": 312, "returned": 5, "truncated": true },
  "grants": [{
    "rank": 1,                                   // Grist order
    "grantId": "098051",
    "title": "...",
    "funder":      { "name": "Wellcome Trust", "fundRefId": "..." },
    "institution": { "name": "University of Cambridge", "department": "...", "nameMatch": "EXACT" },
    "principalInvestigator": "...", "startDate": "...", "endDate": "...",
    "publicationLookup": { "status": "PUBLICATIONS_FOUND", "query": "GRANT_ID:\"098051\"",
                           "totalHitCount": 1261 },
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
  may arrive as `{"$": "..."}`. Missing data becomes `null`, never an exception.
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
4. Results are returned in Grist order, first page only, capped at `limit`.
5. Each grant reports `institution.nameMatch`: `EXACT` if the reported name equals the request
   (ignoring case), otherwise `PARTIAL`. `aff:` also searches departments and partial names, so
   `aff:"Cambridge"` can return institutes that are not the university. `PARTIAL` makes that visible
   instead of hiding it.

Limitations: no synonym or variant expansion ("UCL" and "University College London" are different
searches), no ROR lookup, and Grist only indexes awards from its participating funders. The affiliation
is that of the award holder; a university's grants held elsewhere, or not indexed, are absent.

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

* After the single Grist call, one task per grant is submitted to a virtual-thread executor, so all
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
* No retries. With a 20 s budget and a shared API, a retry storm is a worse failure mode than one
  clearly reported failed lookup.
* The HTTP client uses HTTP/1.1 and the JVM's default `ProxySelector`, so it works behind a
  corporate proxy configured with `-Dhttps.proxyHost`.

All tunables are in `application.yml` and can be overridden with environment variables, for example
`docker run -e PORTFOLIO_ARTICLES_MAX_CONCURRENT=2 ...`.

## Tests

`./mvnw test` runs 39 tests with no network access:

| Test class | What it covers |
|---|---|
| `QueryBuilderTest` | Phrase construction, whitespace cleaning, phrase-injection, accents and `&`, rejected input |
| `HttpClientsTest` | Exact encoded Grist path and Articles URL; parsing; 503 and HTML-body error mapping (MockRestServiceServer) |
| `GristResponseParserTest` | Normal records, single record as an object, missing fields, empty result |
| `PublicationLinkerTest` | Confirmed, funder mismatch, unknown funder, no exact ID, DOI-form IDs |
| `PortfolioServiceTest` | Order preserved under reversed completion; failure and timeout isolation; failed vs none-found; concurrency never exceeds the limit but does overlap; per-grant dedup with cross-grant duplicates kept; grants with no ID; limits |
| `DemoGristClientTest` | Demo fixture filtering by affiliation, case-insensitive partial match, unknown institution |
| `PortfolioControllerTest` | Full Spring wiring: JSON shape, defaults, 400s, 502 and 504 |

## Limitations

* One Grist page (25 records) is read, so at most the first 10 grants in Grist's order are returned.
  `grantSearch.truncated` and `totalHitCount` say when more exist.
* Summary counts describe only the returned sample, and say so in the response.
* Funder matching is string-based. An MRC grant acknowledged as "UK Research and Innovation" (MRC's
  parent body) is reported as `UNCERTAIN_FUNDER_MISMATCH` even though it is probably correct.
* Publications are taken in Articles API relevance order, so uncertain matches can take slots that
  a confirmed match lower down would have filled.
* No caching. Repeated requests for the same university repeat every upstream call.

## Review of my solution

**Which design decision worked particularly well?**
Keeping each lookup's failure inside its own result. Every article task returns a value, never throws,
and results are collected by index. That single choice gave failure isolation, correct ordering under
out-of-order completion, and a clear distinction between `NO_PUBLICATIONS_FOUND` and `LOOKUP_FAILED`.
It also made the behaviour easy to test with plain lambdas instead of an HTTP mock.

**What would I change or improve with more time?**
Better institution matching: resolve the name to a ROR ID, try a small set of known variants, and
rank exact affiliation matches first. I would also add a funder alias table (MRC/UKRI, NIH institutes,
FundRef IDs), a short-lived cache keyed by query, and a contract test run on a schedule against live
Europe PMC to catch schema drift.

**Which assumption about Europe PMC data would I validate before production?**
The shape and stability of Grist's JSON. During development the Grist endpoint closed every connection
(from two different networks and a browser), so the Grist parser was written against the documented
field model and made deliberately tolerant (array or object, optional fields, case-insensitive keys).
The Articles side was verified live, and demo mode (see above) lets the rest of the pipeline be
exercised meanwhile. Before production I would capture real Grist responses for
several universities and add them as fixtures. I would also check how consistently `grantsList`
agency names match Grist funder names, since that decides how many links are `CONFIRMED`.

**Which piece of technical debt did I deliberately accept?**
Funder matching by normalised string comparison instead of a curated funder identity map. It is simple
and transparent, and when it is wrong it errs towards "uncertain" rather than "confirmed", but it will
under-confirm some legitimate links until a proper mapping exists.
