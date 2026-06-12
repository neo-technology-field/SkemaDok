package com.neo4j.skemadok.bulk;

import com.neo4j.skemadok.model.LabelInfo;
import com.neo4j.skemadok.model.PropertyInfo;
import com.neo4j.skemadok.model.SchemaDocument;
import com.neo4j.skemadok.seeder.*;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Writes one Parquet file per {@link SeedPlan.NodeCreate} bucket into the nodes output directory.
 *
 * <p>Each file's Parquet schema encodes the neo4j-admin import column name convention directly
 * as field names: {@code :ID} (INT64), {@code :LABEL} (UTF-8 string), and per-property columns
 * using {@code name:TYPE} field names. HIERARCHY chains write all ancestor labels semicolon-joined
 * into the single {@code :LABEL} column so a single neo4j-admin run creates the right multi-label
 * nodes.
 *
 * <p>Property value generation mirrors the live {@link com.neo4j.skemadok.seeder.SeedExecutor}:
 * uniqueness-constrained properties vary by node index; others get fixed placeholder stubs.
 */
class NodeParquetWriter {

    private static final Logger log = LoggerFactory.getLogger(NodeParquetWriter.class);

    private final Map<String, LabelInfo> labelIndex;
    private final SeedPlan plan;
    private final UniquenessConstraints uniqueness;
    private final PropertyValueGenerator generator;

    NodeParquetWriter(SchemaDocument schema, SeedPlan plan) {
        this.plan = plan;
        this.uniqueness = UniquenessConstraints.from(schema.getConstraints());
        this.generator = new PlaceholderValueGenerator();
        this.labelIndex = new HashMap<>();
        for (var label : schema.getNodeLabels()) {
            labelIndex.put(label.getName(), label);
        }
    }

    List<ExportResult.WrittenFile> writeAll(Path nodesDir) throws IOException {
        var written = new ArrayList<ExportResult.WrittenFile>();
        for (var bucket : plan.nodeCreates()) {
            written.add(writeBucket(bucket, nodesDir));
        }
        return written;
    }

    private ExportResult.WrittenFile writeBucket(SeedPlan.NodeCreate bucket, Path nodesDir) throws IOException {
        var primaryLabel = bucket.labels().getLast();
        var outputPath = nodesDir.resolve(primaryLabel + ".parquet");
        log.info("Writing {} node rows → {}", String.format("%,d", bucket.count()), outputPath.getFileName());

        var propColumns = buildPropertyColumns(bucket);
        var schema = buildSchema(propColumns);
        var labelString = String.join(";", bucket.labels());

        try (var writer = ExampleParquetWriter.builder(new LocalOutputFile(outputPath))
                .withType(schema)
                .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {

            var factory = new SimpleGroupFactory(schema);
            for (long row = 0; row < bucket.count(); row++) {
                var group = factory.newGroup();
                group.add(":ID", bucket.seedIdStart() + row);
                group.add(":LABEL", Binary.fromString(labelString));
                for (var col : propColumns) {
                    var index = bucket.indexForLabel(col.owningLabel(), row);
                    var mustBeUnique = uniqueness.uniquePropertiesOf(col.owningLabel()).contains(col.property().name());
                    var value = generator.generate(col.owningLabel(), col.property(), index, mustBeUnique);
                    ParquetTypeMapper.addValue(group, col.columnName(), value);
                }
                writer.write(group);
            }
        }
        return new ExportResult.WrittenFile(outputPath, bucket.count());
    }

    /**
     * Collects the property columns for a bucket by iterating the label chain root-to-leaf.
     * Leaf (more specific) properties overwrite root properties of the same name, so the
     * subclass's version and owning label win on any conflict.
     */
    private List<PropertyColumn> buildPropertyColumns(SeedPlan.NodeCreate bucket) {
        var propMap = new LinkedHashMap<String, PropertyColumn>();
        for (var label : bucket.labels()) {
            var labelInfo = labelIndex.get(label);
            if (labelInfo == null) {
                continue;
            }
            for (var prop : labelInfo.getProperties()) {
                propMap.put(prop.name(), new PropertyColumn(prop, label));
            }
        }
        return new ArrayList<>(propMap.values());
    }

    private static MessageType buildSchema(List<PropertyColumn> propColumns) {
        var fields = new ArrayList<Type>();
        fields.add(Types.primitive(PrimitiveType.PrimitiveTypeName.INT64, Type.Repetition.REQUIRED)
                .named(":ID"));
        fields.add(Types.primitive(PrimitiveType.PrimitiveTypeName.BINARY, Type.Repetition.REQUIRED)
                .as(LogicalTypeAnnotation.stringType())
                .named(":LABEL"));
        for (var col : propColumns) {
            fields.add(ParquetTypeMapper.optionalPropertyField(col.columnName(), col.property().types()));
        }
        return new MessageType("schema", fields);
    }

    /**
     * A resolved property column: the property definition and the label that owns it in this
     * bucket (determines the per-label index for value generation).
     */
    private record PropertyColumn(PropertyInfo property, String owningLabel) {
        String columnName() {
            return property.name() + ":" + ParquetTypeMapper.neo4jTypeSuffix(property.types());
        }
    }
}
