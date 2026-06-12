package com.neo4j.skemadok.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Root command for the customer-facing collector JAR.
 * Dispatches to the collect subcommand.
 */
@Command(
        name = "skemadok-collector",
        description = "Collect Neo4j schema into a JSON file.",
        mixinStandardHelpOptions = true,
        subcommands = {CollectCommand.class}
)
public class CollectorCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Specify a subcommand: collect");
        System.err.println("Run 'skemadok-collector --help' for usage.");
        return 1;
    }
}
