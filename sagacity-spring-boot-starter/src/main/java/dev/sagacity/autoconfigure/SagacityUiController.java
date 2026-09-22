package dev.sagacity.autoconfigure;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the Sagacity embedded UI at {@code /sagacity/ui}.
 *
 * <p>The UI is a single-page HTML file bundled inside the starter JAR at
 * {@code META-INF/sagacity-ui/index.html}. It talks to the existing
 * {@code /sagacity/**} REST endpoints — no new backend needed.
 *
 * <p>Access: <a href="http://localhost:8080/sagacity/ui">http://localhost:8080/sagacity/ui</a>
 *
 * <p>This is intentionally a plain {@code @RestController} returning the HTML
 * string rather than a {@code ResourceHttpRequestHandler} to avoid conflicting
 * with the application's own static resource configuration.
 */
@RestController
@RequestMapping("/sagacity/ui")
public class SagacityUiController {

    private static final String UI_RESOURCE = "META-INF/sagacity-ui/index.html";

    /**
     * Serve the embedded UI. The HTML page calls {@code /sagacity/workflows},
     * {@code /sagacity/approvals}, and {@code /sagacity/audit/*} — all of which
     * are registered by {@link SagacityAutoConfiguration} and
     * {@link dev.sagacity.workflows.autoconfigure.WorkflowAutoConfiguration}.
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> ui() throws IOException {
        ClassPathResource resource = new ClassPathResource(UI_RESOURCE);
        String html = resource.getContentAsString(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
