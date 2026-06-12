package com.neo4j.skemadok.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Component
@Command(
        name = "skemadok",
        version = "0.1.0",
        description = "Neo4j schema analyser and documentation builder.",
        mixinStandardHelpOptions = true,
        subcommands = {CollectCommand.class, UiCommand.class, GenerateCommand.class, MergeCommand.class, SeedCommand.class, SeedBulkCommand.class}
)
public class SkemaDokCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SkemaDokCommand.class);

    @Override
    public Integer call() {
        log.warn("Specify a subcommand: collect, ui, generate, merge, seed");
        log.warn("Run 'skemadok --help' for usage.");
        return 1;
    }
}
