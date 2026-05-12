package com.neo4j.skemadok.merge;

import com.neo4j.skemadok.model.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Merges a fresh schema snapshot into an annotated schema document.
 * <p>
 * Merge rules:
 * - New labels / relationship types from the snapshot are added.
 * - Entities still present retain their user annotations (descriptions, property descriptions).
 * - Entities no longer in the database are flagged with {@code removed: true} and kept.
 * - Indexes and constraints are always replaced with the snapshot values (no annotations on them).
 * - Views are always preserved from the existing document.
 */
public class SchemaMerger {

    /**
     * @param existing the annotated document held by the user
     * @param incoming the fresh snapshot just collected from Neo4j
     * @return merged document
     */
    public SchemaDocument merge(SchemaDocument existing, SchemaDocument incoming) {

        var merged = new SchemaDocument(
                incoming.getDatabaseAddress(), incoming.getDatabaseName(),
                incoming.getDatabaseVersion(), incoming.getCapturedAt()
        );
        merged.setLastEditedAt(existing.getLastEditedAt());
        merged.setNodeLabels(mergeLabels(existing.getNodeLabels(), incoming.getNodeLabels()));
        merged.setRelationshipTypes(mergeRelTypes(existing.getRelationshipTypes(), incoming.getRelationshipTypes()));
        merged.setIndexes(incoming.getIndexes());
        merged.setConstraints(incoming.getConstraints());
        merged.setViews(existing.getViews());
        return merged;
    }

    private List<LabelInfo> mergeLabels(List<LabelInfo> existing, List<LabelInfo> incoming) {

        Map<String, LabelInfo> existingByName = existing.stream().collect(Collectors.toMap(LabelInfo::getName, l -> l));

        List<LabelInfo> result = new ArrayList<>();

        // Process incoming labels — update or add
        for (LabelInfo newLabel : incoming) {
            LabelInfo old = existingByName.get(newLabel.getName());
            if (old != null) {
                preserveLabelAnnotations(old, newLabel);
            }
            result.add(newLabel);
        }

        // Flag any existing labels not present in the incoming snapshot
        Set<String> incomingNames = incoming.stream().map(LabelInfo::getName).collect(Collectors.toSet());
        for (LabelInfo old : existing) {
            if (!incomingNames.contains(old.getName())) {
                old.setRemoved(true);
                result.add(old);
            }
        }

        result.sort(Comparator.comparing(LabelInfo::getName));
        return result;
    }

    private List<RelationshipTypeInfo> mergeRelTypes(List<RelationshipTypeInfo> existing, List<RelationshipTypeInfo> incoming) {
        Map<String, RelationshipTypeInfo> existingByName = existing.stream().collect(Collectors.toMap(RelationshipTypeInfo::getName, r -> r));

        List<RelationshipTypeInfo> result = new ArrayList<>();

        for (RelationshipTypeInfo newRel : incoming) {
            RelationshipTypeInfo old = existingByName.get(newRel.getName());
            if (old != null) {
                preserveRelAnnotations(old, newRel);
            }
            result.add(newRel);
        }

        Set<String> incomingNames = incoming.stream().map(RelationshipTypeInfo::getName).collect(Collectors.toSet());
        for (RelationshipTypeInfo old : existing) {
            if (!incomingNames.contains(old.getName())) {
                old.setRemoved(true);
                result.add(old);
            }
        }

        result.sort(Comparator.comparing(RelationshipTypeInfo::getName));
        return result;
    }

    /**
     * Copies user-added annotations from old to newLabel (which has fresh structural data).
     */
    private void preserveLabelAnnotations(LabelInfo old, LabelInfo newLabel) {
        if (isPresent(old.getDescription()))    newLabel.setDescription(old.getDescription());
        if (isPresent(old.getDataSource()))     newLabel.setDataSource(old.getDataSource());
        if (isPresent(old.getExtendsLabel()))   newLabel.setExtendsLabel(old.getExtendsLabel());
        if (!old.getTaggedEntities().isEmpty())  newLabel.setTaggedEntities(new ArrayList<>(old.getTaggedEntities()));
        if (isPresent(old.getColor()))          newLabel.setColor(old.getColor());
        if (!old.getDisplayProperties().isEmpty()) {
            newLabel.setDisplayProperties(new ArrayList<>(old.getDisplayProperties()));
        }
        mergePropertyAnnotations(old.getProperties(), newLabel.getProperties());
    }

    private void preserveRelAnnotations(RelationshipTypeInfo old, RelationshipTypeInfo newRel) {
        if (isPresent(old.getDescription())) newRel.setDescription(old.getDescription());
        if (isPresent(old.getDataSource()))  newRel.setDataSource(old.getDataSource());
        if (!old.getDisplayProperties().isEmpty()) {
            newRel.setDisplayProperties(new ArrayList<>(old.getDisplayProperties()));
        }
        mergePropertyAnnotations(old.getProperties(), newRel.getProperties());
        mergeTypeParameterAnnotations(old.getTypeParameters(), newRel.getTypeParameters());
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Copies user-provided variable names and descriptions for type parameter slots from the old entry.
     * Matched by position; unmatched positions in the new entry are left as-is.
     */
    private void mergeTypeParameterAnnotations(List<TypeParameter> oldParams, List<TypeParameter> newParams) {
        if (oldParams == null || newParams == null) return;
        Map<Integer, TypeParameter> oldByPosition = oldParams.stream()
                .collect(Collectors.toMap(TypeParameter::position, p -> p));
        for (int i = 0; i < newParams.size(); i++) {
            TypeParameter newParam = newParams.get(i);
            TypeParameter old = oldByPosition.get(newParam.position());
            if (old == null) continue;
            var updated = newParam;
            // Preserve user-assigned name when it differs from the positional default
            String defaultName = "v" + (newParam.position() + 1);
            if (!old.name().equals(defaultName)) {
                updated = updated.withName(old.name());
            }
            if (!old.description().isBlank()) {
                updated = updated.withDescription(old.description());
            }
            if (updated != newParam) {
                newParams.set(i, updated);
            }
        }
    }

    /**
     * Copies property annotations from old list to matching properties in the new list.
     */
    private void mergePropertyAnnotations(List<PropertyInfo> oldProps, List<PropertyInfo> newProps) {
        Map<String, PropertyInfo> oldByName = oldProps.stream()
                .collect(Collectors.toMap(PropertyInfo::name, p -> p));

        for (int i = 0; i < newProps.size(); i++) {
            PropertyInfo newProp = newProps.get(i);
            PropertyInfo oldProp = oldByName.get(newProp.name());
            if (oldProp != null) {
                var desc = isPresent(oldProp.description()) ? oldProp.description() : newProp.description();
                var src  = isPresent(oldProp.dataSource())  ? oldProp.dataSource()  : newProp.dataSource();
                if (!desc.equals(newProp.description()) || !src.equals(newProp.dataSource())) {
                    newProps.set(i, newProp.withAnnotations(desc, src));
                }
            }
        }
    }
}
