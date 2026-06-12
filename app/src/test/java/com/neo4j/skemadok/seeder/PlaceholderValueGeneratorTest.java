package com.neo4j.skemadok.seeder;

import com.neo4j.skemadok.model.PropertyInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaceholderValueGeneratorTest {

    private final PlaceholderValueGenerator generator = new PlaceholderValueGenerator();

    // ---------- non-unique (constant placeholders) ----------

    @Test
    void primitiveTypesMapToTypeAppropriateStubs() {
        assertEquals("text",
                generator.generate("X", new PropertyInfo("name", List.of("String"), false), 0, false));
        assertEquals(0L,
                generator.generate("X", new PropertyInfo("age", List.of("Long"), false), 0, false));
        assertEquals(0L,
                generator.generate("X", new PropertyInfo("age", List.of("Integer"), false), 0, false));
        assertEquals(0.0d,
                generator.generate("X", new PropertyInfo("ratio", List.of("Double"), false), 0, false));
        assertEquals(Boolean.FALSE,
                generator.generate("X", new PropertyInfo("active", List.of("Boolean"), false), 0, false));
    }

    @Test
    void temporalTypesProduceJavaTimeStubs() {
        assertEquals(LocalDate.of(2024, 1, 1),
                generator.generate("X", new PropertyInfo("dob", List.of("Date"), false), 0, false));
        assertEquals(ZonedDateTime.parse("2026-05-17T00:00:00Z"),
                generator.generate("X", new PropertyInfo("ts", List.of("DateTime"), false), 0, false));
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0),
                generator.generate("X", new PropertyInfo("ts", List.of("LocalDateTime"), false), 0, false));
    }

    @Test
    void unsupportedSpatialTypesReturnNullSoTheyAreSkipped() {
        assertNull(generator.generate("X", new PropertyInfo("location", List.of("Point"), true), 0, false));
        assertNull(generator.generate("X", new PropertyInfo("dur", List.of("Duration"), true), 0, false));
    }

    @Test
    void firstTypeWinsForDriftedSchemas() {
        var drifted = new PropertyInfo("amount", List.of("Long", "String"), false);
        assertEquals(0L, generator.generate("X", drifted, 0, false));
    }

    @Test
    void missingTypeListFallsBackToString() {
        var noType = new PropertyInfo("blob", List.of(), false);
        assertEquals("text", generator.generate("X", noType, 0, false));
    }

    // ---------- unique (vary by index) ----------

    @Test
    void uniqueStringIsJustTheIndexAsText() {
        var prop = new PropertyInfo("id", List.of("String"), false);
        assertEquals("0",  generator.generate("Person", prop, 0, true));
        assertEquals("42", generator.generate("Person", prop, 42, true));
        // owner / property name are deliberately not in the output — uniqueness is per (label,prop).
        assertEquals("42", generator.generate("Company", prop, 42, true));
    }

    @Test
    void uniqueNumericTypesAreTheIndexCoerced() {
        var longProp = new PropertyInfo("n", List.of("Long"), false);
        assertEquals(0L,  generator.generate("X", longProp, 0,  true));
        assertEquals(42L, generator.generate("X", longProp, 42, true));

        var doubleProp = new PropertyInfo("d", List.of("Double"), false);
        assertEquals(0.0d,  generator.generate("X", doubleProp, 0,  true));
        assertEquals(42.0d, generator.generate("X", doubleProp, 42, true));
    }

    @Test
    void uniqueDateMovesByEpochDayPerIndex() {
        var prop = new PropertyInfo("d", List.of("Date"), false);
        assertEquals(LocalDate.of(2024, 1, 1), generator.generate("X", prop, 0, true));
        assertEquals(LocalDate.of(2024, 1, 2), generator.generate("X", prop, 1, true));
        assertEquals(LocalDate.of(2024, 1, 1).plusDays(100), generator.generate("X", prop, 100, true));
    }

    @Test
    void uniqueDateTimeAdvancesBySecondsPerIndex() {
        var prop = new PropertyInfo("ts", List.of("DateTime"), false);
        var base = ZonedDateTime.parse("2026-05-17T00:00:00Z");
        assertEquals(base,                  generator.generate("X", prop, 0,   true));
        assertEquals(base.plusSeconds(1),   generator.generate("X", prop, 1,   true));
        assertEquals(base.plusSeconds(500), generator.generate("X", prop, 500, true));
    }

    @Test
    void uniqueStringArrayWrapsTheIndexString() {
        var prop = new PropertyInfo("tags", List.of("StringArray"), false);
        assertEquals(List.of("0"),  generator.generate("X", prop, 0, true));
        assertEquals(List.of("99"), generator.generate("X", prop, 99, true));
    }

    @Test
    void uniqueLongArrayWrapsTheIndex() {
        var prop = new PropertyInfo("codes", List.of("LongArray"), false);
        assertEquals(List.of(0L),  generator.generate("X", prop, 0, true));
        assertEquals(List.of(7L),  generator.generate("X", prop, 7, true));
    }

    @Test
    void uniqueBooleanFallsBackWithWarning() {
        var prop = new PropertyInfo("flag", List.of("Boolean"), false);
        // Boolean uniqueness with >2 entities is impossible; the generator returns false and warns.
        assertEquals(Boolean.FALSE, generator.generate("X", prop, 0, true));
        assertEquals(Boolean.FALSE, generator.generate("X", prop, 5, true));
    }

    @Test
    void uniquePointAndDurationReturnNull() {
        var pointProp = new PropertyInfo("loc", List.of("Point"), false);
        var durProp = new PropertyInfo("dur", List.of("Duration"), false);
        assertNull(generator.generate("X", pointProp, 0, true));
        assertNull(generator.generate("X", durProp, 0, true));
    }
}
