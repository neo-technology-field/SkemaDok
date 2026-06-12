package com.neo4j.skemadok;

import com.neo4j.skemadok.cli.SkemaDokCommand;
import com.neo4j.skemadok.cli.UiCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;

import java.util.Arrays;

@SpringBootApplication
public class SkemaDokApplication {

    public static void main(String[] args) {
        // Determine web type before Spring starts — only the 'ui' subcommand needs a web server.
        boolean uiMode = Arrays.asList(args).contains(UiCommand.COMMAND_NAME);

        new SpringApplicationBuilder(SkemaDokApplication.class)
                .web(uiMode ? org.springframework.boot.WebApplicationType.SERVLET : WebApplicationType.NONE)
                .run(args);
    }

    /**
     * Executes picocli after the Spring context is fully initialised.
     * For CLI subcommands (collect, generate, merge) the process exits after completion.
     * For 'ui', the web server stays alive and the process runs until Ctrl+C.
     */
    @Bean
    CommandLineRunner commandLineRunner(ApplicationContext ctx, SkemaDokCommand rootCommand) {
        return args -> {
            // Spring bean factory — delegates to ApplicationContext for @Component commands,
            // falls back to picocli default factory for internal picocli types.
            // Must be an anonymous class because IFactory.create() is a generic method.
            CommandLine.IFactory factory = new CommandLine.IFactory() {
                @Override
                public <K> K create(Class<K> clazz) throws Exception {
                    try {
                        return ctx.getBean(clazz);
                    } catch (Exception e) {
                        return CommandLine.defaultFactory().create(clazz);
                    }
                }
            };

            int exitCode = new CommandLine(rootCommand, factory).execute(args);

            if (Arrays.stream(args).noneMatch(UiCommand.COMMAND_NAME::equals)) {
                System.exit(exitCode);
            }
        };
    }
}
