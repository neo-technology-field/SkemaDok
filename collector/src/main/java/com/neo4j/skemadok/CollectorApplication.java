package com.neo4j.skemadok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neo4j.skemadok.cli.CollectCommand;
import com.neo4j.skemadok.cli.CollectorCommand;
import com.neo4j.skemadok.cli.MergeCommand;
import com.neo4j.skemadok.collector.SchemaCollector;
import com.neo4j.skemadok.merge.SchemaMerger;
import picocli.CommandLine;

/**
 * Entry point for the thin collector JAR — no Spring context, no web server.
 * Ships to customers who need to capture a schema snapshot on their own infrastructure.
 */
public class CollectorApplication {

    public static void main(String[] args) {
        var objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        var collector = new SchemaCollector();
        var merger = new SchemaMerger();

        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> clazz) throws Exception {
                if (clazz == CollectCommand.class) {
                    return clazz.cast(new CollectCommand(collector, objectMapper));
                }
                if (clazz == MergeCommand.class) {
                    return clazz.cast(new MergeCommand(merger, objectMapper));
                }
                return CommandLine.defaultFactory().create(clazz);
            }
        };

        int exitCode = new CommandLine(new CollectorCommand(), factory).execute(args);
        System.exit(exitCode);
    }
}
