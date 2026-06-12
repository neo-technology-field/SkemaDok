package com.neo4j.skemadok.cli;

import com.neo4j.skemadok.service.SchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Loads the schema file and reports the UI URL once the embedded server is ready.
 * The Spring Boot web server is already running by the time this command executes.
 */
@Component
@Command(
        name = UiCommand.COMMAND_NAME,
        description = "Launch the interactive schema canvas in a browser.",
        mixinStandardHelpOptions = true
)
public class UiCommand implements Callable<Integer> {

    public static final String COMMAND_NAME = "ui";

    private static final Logger log = LoggerFactory.getLogger(UiCommand.class);

    @Option(names = {"-s", "--schema"}, required = true,
            description = "Path to the schema JSON file produced by 'collect'")
    private Path schemaFile;

    private final SchemaService schemaService;
    private final int serverPort;

    public UiCommand(SchemaService schemaService, @Value("${server.port}") int serverPort) {
        this.schemaService = schemaService;
        this.serverPort = serverPort;
    }

    @Override
    public Integer call() {
        try {
            schemaService.setFilePath(schemaFile);
            log.info("SkemaDok UI running at: http://localhost:{}", serverPort);
            log.info("Press Ctrl+C to stop.");
            return 0;
        } catch (Exception e) {
            log.error("Failed to start UI: {}", e.getMessage());
            return 1;
        }
    }
}
