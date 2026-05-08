package com.neo4j.skemadok.server;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards all SPA routes to index.html so Vue Router can handle client-side navigation.
 *
 * <p>The path pattern {@code [^\\.]*} matches segments that contain no dot — this excludes
 * static assets (CSS, JS, images all have file extensions) while catching Vue routes like
 * {@code /views}, {@code /metadata}, {@code /generate}. API routes are matched by the
 * {@code @RestController} endpoints, which have higher specificity than this catch-all.
 */
@Controller
public class SpaController {

    @GetMapping({"/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String forwardToSpa() {
        return "forward:/index.html";
    }
}
