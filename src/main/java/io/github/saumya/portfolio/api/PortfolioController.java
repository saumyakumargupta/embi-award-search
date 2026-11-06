package io.github.saumya.portfolio.api;

import io.github.saumya.portfolio.service.PortfolioService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController {

    static final int DEFAULT_LIMIT = 5;
    static final int DEFAULT_PUBLICATIONS_PER_GRANT = 3;

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    /**
     * {@code GET /institution-portfolio?institution=University%20of%20Cambridge&limit=5&publicationsPerGrant=3}
     *
     * @param institution          plain institution name; no Grist syntax needed
     * @param limit                grants to return, 1-10 (default 5)
     * @param publicationsPerGrant publications per grant, 1-5 (default 3)
     */
    @GetMapping(path = "/institution-portfolio", produces = MediaType.APPLICATION_JSON_VALUE)
    public PortfolioResponse portfolio(
            @RequestParam String institution,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "" + DEFAULT_PUBLICATIONS_PER_GRANT) int publicationsPerGrant) {
        return service.buildPortfolio(institution, limit, publicationsPerGrant);
    }
}
