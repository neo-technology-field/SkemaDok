package com.neo4j.skemadok.bulk;

import com.neo4j.skemadok.model.PropertyInfo;
import com.neo4j.skemadok.model.RelationshipTypeInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.seeder.PlaceholderValueGenerator;
import com.neo4j.skemadok.seeder.PropertyValueGenerator;
import com.neo4j.skemadok.seeder.SeedOptions;
import com.neo4j.skemadok.seeder.SeedPlan;
import com.neo4j.skemadok.seeder.UniquenessConstraints;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes one Parquet file per {@link SeedPlan.RelPlan} entry into the relationships output directory.
 *
 * <p>Relationship endpoint IDs are sampled uniformly at random from the start and end label's
 * {@link SeedPlan#labelRanges()} — the same ID space as node files, so foreign keys resolve
 * correctly after import. Parameterised relationship type instances resolve their properties
 * from the base type entry in the schema.
 */
class RelationshipParquetWriter {

    private static final Logger log = LoggerFactory.getLogger(RelationshipParquetWriter.class);

    private final SeedPlan plan;
    private final Map<String, RelationshipTypeInfo> relTypeIndex;
    private final UniquenessConstraints uniqueness;
    private final PropertyValueGenerator generator;
    private final Random random;

    RelationshipParquetWriter(SchemaDocument schema, SeedPlan plan, SeedOptions options) {
        this.plan = plan;
        this.uniqueness = UniquenessConstraints.from(schema.getConstraints());
        this.generator = new PlaceholderValueGenerator();
        this.random = new Random(options.randomSeed());
        this.relTypeIndex = buildRelTypeIndex(schema.getRelationshipTypes());
    }

    /**
     * Indexes relationship types by both their canonical name and every parameterised instance name,
     * so a {@link SeedPlan.RelPlan} referring to an instance can still look up the base type's
     * properties.
     */
    private static Map<String, RelationshipTypeInfo> buildRelTypeIndex(List<RelationshipTypeInfo> relTypes) {
        var index = new HashMap<String, RelationshipTypeInfo>();
        for (var rtype : relTypes) {
            index.put(rtype.getName(), rtype);
            if (rtype.getInstances() != null) {
                for (var instance : rtype.getInstances()) {
                    index.put(instance, rtype);
                }
            }
        }
        return index;
    }

    List<ExportResult.WrittenFile> writeAll(Path relsDir) throws IOException {
        // Group by rel type: the same type can appear in multiple RelPlan entries with different
        // start/end labels (e.g. OWNED_BY from both Application and CloudAccount).
        // All entries for the same type go into one file — neo4j-admin handles mixed connectivity.
        var byType = new java.util.LinkedHashMap<String, List<SeedPlan.RelPlan>>();
        for (var relPlan : plan.rels()) {
            byType.computeIfAbsent(relPlan.relType(), k -> new ArrayList<>()).add(relPlan);
        }

        var written = new ArrayList<ExportResult.WrittenFile>();
        for (var entry : byType.entrySet()) {
            written.add(writeRelFile(entry.getKey(), entry.getValue(), relsDir));
        }
        return written;
    }

    private ExportResult.WrittenFile writeRelFile(String relType, List<SeedPlan.RelPlan> plans, Path relsDir)
            throws IOException {
        var outputPath = relsDir.resolve(relType + ".parquet");
        long totalRows = plans.stream().mapToLong(SeedPlan.RelPlan::target).sum();
        log.info("Writing {} relationship rows → {}", String.format("%,d", totalRows), outputPath.getFileName());

        var relTypeInfo = relTypeIndex.get(relType);
        var properties = relTypeInfo != null ? relTypeInfo.getProperties() : List.<PropertyInfo>of();
        var schema = buildSchema(relType, properties);

        try (var writer = ExampleParquetWriter.builder(new LocalOutputFile(outputPath))
                .withType(schema)
                .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {

            var factory = new SimpleGroupFactory(schema);
            long globalRow = 0;
            for (var relPlan : plans) {
                var startRanges = plan.labelRanges().get(relPlan.startLabel());
                var endRanges = plan.labelRanges().get(relPlan.endLabel());
                for (long row = 0; row < relPlan.target(); row++, globalRow++) {
                    var group = factory.newGroup();
                    group.add(":START_ID", pickRandomId(startRanges));
                    group.add(":END_ID", pickRandomId(endRanges));
                    group.add(":TYPE", Binary.fromString(relType));
                    for (var prop : properties) {
                        var columnName = prop.name() + ":" + ParquetTypeMapper.neo4jTypeSuffix(prop.types());
                        var mustBeUnique = uniqueness.uniquePropertiesOf(relType).contains(prop.name());
                        var value = generator.generate(relType, prop, globalRow, mustBeUnique);
                        ParquetTypeMapper.addValue(group, columnName, value);
                    }
                    writer.write(group);
                }
            }
        }
        return new ExportResult.WrittenFile(outputPath, totalRows);
    }

    private static MessageType buildSchema(String relType, List<PropertyInfo> properties) {
        var fields = new ArrayList<Type>();
        fields.add(Types.primitive(PrimitiveType.PrimitiveTypeName.INT64, Type.Repetition.REQUIRED)
                .named(":START_ID"));
        fields.add(Types.primitive(PrimitiveType.PrimitiveTypeName.INT64, Type.Repetition.REQUIRED)
                .named(":END_ID"));
        fields.add(Types.primitive(PrimitiveType.PrimitiveTypeName.BINARY, Type.Repetition.REQUIRED)
                .as(LogicalTypeAnnotation.stringType())
                .named(":TYPE"));
        for (var prop : properties) {
            var columnName = prop.name() + ":" + ParquetTypeMapper.neo4jTypeSuffix(prop.types());
            fields.add(ParquetTypeMapper.optionalPropertyField(columnName, prop.types()));
        }
        return new MessageType("schema", fields);
    }

    /**
     * Picks a uniformly random ID from across all ranges for a label. Ranges are non-overlapping
     * contiguous seedId slices (as produced by {@link SeedPlan#labelRanges()}), so uniform
     * sampling across the aggregate span is equivalent to uniform sampling across the physical
     * nodes carrying that label.
     */
    private long pickRandomId(List<long[]> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (var range : ranges) {
            total += range[1] - range[0];
        }
        long pick = (long) (random.nextDouble() * total);
        for (var range : ranges) {
            var size = range[1] - range[0];
            if (pick < size) {
                return range[0] + pick;
            }
            pick -= size;
        }
        var last = ranges.getLast();
        return last[1] - 1;
    }
}
