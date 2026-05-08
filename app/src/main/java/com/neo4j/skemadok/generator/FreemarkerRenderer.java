package com.neo4j.skemadok.generator;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.Map;

/**
 * Thin wrapper around a Freemarker {@link Configuration} configured for classpath templates.
 *
 * <p>Templates live under {@code src/main/resources/templates/generator/} and are loaded
 * relative to that root. Uses the core Freemarker library only — no Spring MVC integration,
 * so no view resolver is registered.</p>
 */
@Component
public class FreemarkerRenderer {

    private final Configuration config;

    FreemarkerRenderer() {
        var cfg = new Configuration(Configuration.VERSION_2_3_34);
        cfg.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates/generator");
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);
        this.config = cfg;
    }

    /**
     * Renders the named template with the provided model and returns the result as a string.
     * Template path is relative to {@code templates/generator/}, e.g. {@code "asciidoc/label.ftl"}.
     *
     * @throws IllegalStateException if Freemarker fails to load or process the template
     */
    String render(String templatePath, Map<String, Object> model) {
        try {
            Template template = config.getTemplate(templatePath);
            var writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render template: " + templatePath, e);
        }
    }
}
