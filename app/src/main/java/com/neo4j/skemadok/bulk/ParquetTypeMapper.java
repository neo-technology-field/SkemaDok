package com.neo4j.skemadok.bulk;

import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps SkemaDok property types to Parquet physical types and neo4j-admin column name suffixes.
 *
 * <p>All integer variants use INT64 (not INT32) to avoid signed-overflow issues and because
 * neo4j-admin coerces the value based on the column-name type hint, not the Parquet physical type.
 *
 * <p>Temporal and array types are serialised as UTF-8 strings. Neo4j's {@code import full} accepts
 * ISO-8601 strings for temporal columns and semicolon-separated strings for array columns.
 */
final class ParquetTypeMapper {

    private ParquetTypeMapper() {}

    /**
     * Returns the column name suffix for use in neo4j-admin import headers.
     * For schema-drift properties (multiple observed types), falls back to {@code STRING}.
     */
    static String neo4jTypeSuffix(List<String> types) {
        if (types == null || types.size() != 1) {
            return "STRING";
        }
        return switch (types.getFirst()) {
            case "Long" -> "LONG";
            case "Integer", "Int" -> "INT";
            case "Double", "Float" -> "FLOAT";
            case "Boolean" -> "BOOLEAN";
            case "Date" -> "DATE";
            case "LocalDateTime" -> "LOCALDATETIME";
            case "DateTime", "ZonedDateTime" -> "DATETIME";
            case "Time" -> "TIME";
            case "LocalTime" -> "LOCALTIME";
            case "StringArray" -> "STRING[]";
            case "LongArray", "IntegerArray" -> "LONG[]";
            case "DoubleArray", "FloatArray" -> "FLOAT[]";
            case "BooleanArray" -> "BOOLEAN[]";
            default -> "STRING";
        };
    }

    /**
     * Builds an optional Parquet field for a property column. The field name encodes the
     * neo4j-admin type hint (e.g. {@code age:LONG}).
     */
    static Type optionalPropertyField(String columnName, List<String> types) {
        var skemaDokType = (types == null || types.size() != 1)
                ? "String" : types.getFirst();
        return switch (skemaDokType) {
            case "Long", "Integer", "Int" ->
                    Types.primitive(PrimitiveType.PrimitiveTypeName.INT64, Type.Repetition.OPTIONAL)
                            .named(columnName);
            case "Double" ->
                    Types.primitive(PrimitiveType.PrimitiveTypeName.DOUBLE, Type.Repetition.OPTIONAL)
                            .named(columnName);
            case "Float" ->
                    Types.primitive(PrimitiveType.PrimitiveTypeName.FLOAT, Type.Repetition.OPTIONAL)
                            .named(columnName);
            case "Boolean" ->
                    Types.primitive(PrimitiveType.PrimitiveTypeName.BOOLEAN, Type.Repetition.OPTIONAL)
                            .named(columnName);
            default ->
                    Types.primitive(PrimitiveType.PrimitiveTypeName.BINARY, Type.Repetition.OPTIONAL)
                            .as(LogicalTypeAnnotation.stringType())
                            .named(columnName);
        };
    }

    /**
     * Adds a property value to {@code group} under {@code fieldName}.
     *
     * <p>Dispatches based on the <em>Parquet field type</em> declared in the group's schema,
     * not the Java type of {@code value}. This is necessary because schema-drift properties
     * (multiple observed types) are declared as BINARY in the schema but the value generator
     * may still produce a numeric Java type for the dominant type — writing a double to a BINARY
     * column throws {@link UnsupportedOperationException} inside Parquet's column writer.
     *
     * <p>Null values are silently skipped — the optional field remains absent in the row.
     */
    static void addValue(org.apache.parquet.example.data.Group group, String fieldName, Object value) {
        if (value == null) {
            return;
        }
        var schemaType = group.getType().getType(fieldName);
        if (!schemaType.isPrimitive()) {
            return;
        }
        switch (schemaType.asPrimitiveType().getPrimitiveTypeName()) {
            case INT64 -> group.add(fieldName, toLong(value));
            case INT32 -> group.add(fieldName, (int) toLong(value));
            case DOUBLE -> group.add(fieldName, toDouble(value));
            case FLOAT -> group.add(fieldName, toFloat(value));
            case BOOLEAN -> group.add(fieldName, toBoolean(value));
            default -> {
                // BINARY — covers UTF-8 strings, temporal values serialised as ISO-8601,
                // array values serialised as semicolon-separated strings, and schema-drift fallbacks.
                if (value instanceof List<?> list) {
                    group.add(fieldName, Binary.fromString(
                            list.stream().map(Object::toString).collect(Collectors.joining(";"))));
                } else {
                    group.add(fieldName, Binary.fromString(value.toString()));
                }
            }
        }
    }

    private static long toLong(Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return (long) i;
        }
        if (v instanceof Double d) {
            return d.longValue();
        }
        return 0L;
    }

    private static double toDouble(Object v) {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Long l) {
            return (double) l;
        }
        if (v instanceof Float f) {
            return (double) f;
        }
        return 0.0;
    }

    private static float toFloat(Object v) {
        if (v instanceof Float f) {
            return f;
        }
        if (v instanceof Double d) {
            return d.floatValue();
        }
        if (v instanceof Long l) {
            return (float) l;
        }
        return 0.0f;
    }

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return false;
    }
}
