package com.neo4j.skemadok.cli;

import com.neo4j.skemadok.service.SchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Starts the embedded HTTP server and opens the schema canvas in the default browser.
 * The Spring Boot web server is already running by the time this command executes;
 * this command loads the schema file and notifies the user.
 */
@Component
@Command(
        name = "ui",
        description = "Launch the interactive schema canvas in a browser.",
        mixinStandardHelpOptions = true
)
public class UiCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(UiCommand.class);

    @Option(names = {"-s", "--schema"}, required = true,
            description = "Path to the schema JSON file produced by 'collect'")
    private Path schemaFile;

    private final SchemaService schemaService;

    public UiCommand(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Override
    public Integer call() {
        try {
            schemaService.setFilePath(schemaFile);
            String url = "http://localhost:8282";
            log.info("SkemaDok UI running at: {}", url);
            log.info("Press Ctrl+C to stop.");

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
            return 0;
        } catch (Exception e) {
            log.error("Failed to start UI: {}", e.getMessage());
            return 1;
        }
    }
}
