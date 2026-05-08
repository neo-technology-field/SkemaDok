package com.neo4j.skemadok.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
        name = "skemadok-collector",
        version = "0.1.0",
        description = "Collect and merge Neo4j schema into a JSON file.",
        mixinStandardHelpOptions = true,
        subcommands = {CollectCommand.class, MergeCommand.class}
)
public class CollectorCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Specify a subcommand: collect, merge");
        System.err.println("Run 'skemadok-collector --help' for usage.");
        return 1;
    }
}
