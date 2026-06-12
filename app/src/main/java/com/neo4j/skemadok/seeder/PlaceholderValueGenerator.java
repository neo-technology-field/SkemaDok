package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.PropertyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Type-aware stubs sufficient for topology-only seeding.
 *
 * <p>For properties not under a uniqueness obligation the generator returns a fixed, type-
 * appropriate stub (all strings become {@code "text"}, all longs become {@code 0L}, …). The
 * intent is to satisfy the schema's type and nullability shape, not to produce realistic data.
 *
 * <p>For properties that participate in a UNIQUENESS or KEY constraint the generator varies the
 * returned value by {@code index} so the seeded batch can satisfy the constraint without
 * collisions. Boolean and spatial / duration types are not unique-able and are flagged with a
 * warning — the downstream constraint creation will then fail loudly and informatively rather
 * than silently producing duplicates.
 */
public class PlaceholderValueGenerator implements PropertyValueGenerator {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderValueGenerator.class);

    private static final LocalDate DATE_BASE = LocalDate.of(2024, 1, 1);
    private static final ZonedDateTime ZONED_BASE = ZonedDateTime.parse("2026-05-17T00:00:00Z");
    private static final LocalDateTime LOCAL_BASE = LocalDateTime.of(2024, 1, 1, 0, 0);
    private static final OffsetTime OFFSET_TIME_BASE = OffsetTime.parse("00:00:00Z");
    private static final LocalTime LOCAL_TIME_BASE = LocalTime.of(0, 0);

    @Override
    public Object generate(String ownerName, PropertyInfo property, long index, boolean mustBeUnique) {
        var type = firstType(property.types());
        if (mustBeUnique) {
            return generateUnique(ownerName, property, type, index);
        }
        return generatePlaceholder(type);
    }

    private static Object generatePlaceholder(String type) {
        return switch (type) {
            case "Long", "Integer", "Int" -> 0L;
            case "Double", "Float" -> 0.0d;
            case "Boolean" -> Boolean.FALSE;
            case "Date" -> DATE_BASE;
            case "DateTime", "ZonedDateTime" -> ZONED_BASE;
            case "LocalDateTime" -> LOCAL_BASE;
            case "Time" -> OFFSET_TIME_BASE;
            case "LocalTime" -> LOCAL_TIME_BASE;
            case "Point", "Duration" -> null;
            case "StringArray" -> List.of("text");
            case "LongArray", "IntegerArray" -> List.of(0L);
            case "DoubleArray", "FloatArray" -> List.of(0.0d);
            case "BooleanArray" -> List.of(Boolean.FALSE);
            default -> "text";
        };
    }

    private static Object generateUnique(String ownerName, PropertyInfo property, String type, long index) {
        return switch (type) {
            case "Long", "Integer", "Int" -> index;
            case "Double", "Float" -> (double) index;
            case "Boolean" -> {
                log.warn("Property {}.{} is Boolean-typed and participates in a uniqueness constraint; "
                        + "uniqueness across more than two entities is unsatisfiable for booleans. "
                        + "Falling back to a non-unique value — the constraint will fail to create.",
                        ownerName, property.name());
                yield Boolean.FALSE;
            }
            case "Date" -> DATE_BASE.plusDays(index);
            case "DateTime", "ZonedDateTime" -> ZONED_BASE.plus(Duration.ofSeconds(index));
            case "LocalDateTime" -> LOCAL_BASE.plus(Duration.ofSeconds(index));
            case "Time" -> OFFSET_TIME_BASE.plus(Duration.ofSeconds(index % 86400));
            case "LocalTime" -> LOCAL_TIME_BASE.plus(Duration.ofSeconds(index % 86400));
            case "Point", "Duration" -> {
                log.warn("Property {}.{} of type {} participates in a uniqueness constraint but the "
                        + "seeder has no per-index generator for this type. Property will be skipped; "
                        + "the constraint will fail to create.",
                        ownerName, property.name(), type);
                yield null;
            }
            case "StringArray" -> List.of(String.valueOf(index));
            case "LongArray", "IntegerArray" -> List.of(index);
            case "DoubleArray", "FloatArray" -> List.of((double) index);
            case "BooleanArray" -> {
                log.warn("Property {}.{} is BooleanArray-typed and participates in a uniqueness "
                        + "constraint; cannot vary by index. Falling back to a non-unique value.",
                        ownerName, property.name());
                yield List.of(Boolean.FALSE);
            }
            default -> String.valueOf(index);
        };
    }

    private static String firstType(List<String> types) {
        if (types == null || types.isEmpty()) {
            return "String";
        }
        return types.getFirst();
    }
}
