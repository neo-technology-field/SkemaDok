package com.neo4j.skemadok.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neo4j.skemadok.cli.CollectCommand;
import com.neo4j.skemadok.cli.MergeCommand;
import com.neo4j.skemadok.collector.SchemaCollector;
import com.neo4j.skemadok.merge.SchemaMerger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /**
     * Explicit ObjectMapper bean — required because JacksonAutoConfiguration is not applied
     * in WebApplicationType.NONE mode (Spring Boot 4.x). Configures JSR-310 (Instant → ISO-8601)
     * and pretty-print for the human-readable schema.json output.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // These classes live in skemadok-core and have no Spring annotations.
    // They are registered here so the Spring DI factory in SkemaDokApplication can resolve them.

    @Bean
    public SchemaCollector schemaCollector() {
        return new SchemaCollector();
    }

    @Bean
    public SchemaMerger schemaMerger() {
        return new SchemaMerger();
    }

    @Bean
    public CollectCommand collectCommand(SchemaCollector schemaCollector, ObjectMapper objectMapper) {
        return new CollectCommand(schemaCollector, objectMapper);
    }

    @Bean
    public MergeCommand mergeCommand(SchemaMerger schemaMerger, ObjectMapper objectMapper) {
        return new MergeCommand(schemaMerger, objectMapper);
    }
}
