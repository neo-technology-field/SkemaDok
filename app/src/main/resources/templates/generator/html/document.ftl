<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${doc.databaseName?html} — Schema Documentation</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 960px; margin: 0 auto; padding: 2rem; color: #1f2328; line-height: 1.6; }
  h1 { font-size: 1.8rem; border-bottom: 1px solid #d0d7de; padding-bottom: 0.5rem; }
  h2 { font-size: 1.4rem; margin-top: 2.5rem; border-bottom: 1px solid #d0d7de; padding-bottom: 0.25rem; }
  h3 { font-size: 1.1rem; margin-top: 1.8rem; }
  code { background: #f6f8fa; padding: 0.1em 0.3em; border-radius: 3px; font-family: ui-monospace, monospace; font-size: 0.9em; }
  table { border-collapse: collapse; width: 100%; margin: 1rem 0; font-size: 0.9rem; }
  th, td { border: 1px solid #d0d7de; padding: 6px 12px; text-align: left; }
  th { background: #f6f8fa; font-weight: 600; }
  tr:nth-child(even) td { background: #f6f8fa; }
  .view-img { max-width: 100%; height: auto; border: 1px solid #d0d7de; border-radius: 4px; margin: 1rem 0; }
  .meta { color: #57606a; font-size: 0.9rem; }
  hr { border: none; border-top: 1px solid #d0d7de; margin: 2rem 0; }
</style>
</head>
<body>

<h1>${doc.databaseName?html} — Schema Documentation</h1>
<p class="meta"><strong>Database:</strong> <code>${doc.databaseAddress?html}</code> / <code>${doc.databaseName?html}</code></p>
<p class="meta"><strong>Captured:</strong> ${capturedAt?html}</p>
<hr>

<#if doc.views?has_content>
<h2>Views</h2>
<#list doc.views as view>
<h3>${view.name?html}</h3>
<#if view.description?has_content>
<p>${view.description?html}</p>
</#if>
<#assign imgData = imagePaths[view.name]!'' />
<#if imgData?has_content>
<img src="data:image/png;base64,${imgData}" alt="${view.name?html}" class="view-img">
</#if>
</#list>
</#if>

<#if doc.nodeLabels?has_content>
<h2>Node Labels</h2>
<#list doc.nodeLabels as label>
<#assign viewNames = labelMembership[label.name]![] />
<h3><code>${label.name?html}</code></h3>
<p>Role: <strong>${(label.role!"ENTITY")?html}</strong> — ${label.nodeCount} nodes<#if viewNames?has_content> | <em>Appears in: ${viewNames?join(", ")?html}</em></#if></p>
<#if label.dataSource?has_content && includeDataSource>
<p><em>Source: ${label.dataSource?html}</em></p>
</#if>
<#if label.description?has_content>
<p>${label.description?html}</p>
</#if>
<#if label.properties?has_content>
<table>
<thead><tr><th>Property</th><th>Type(s)</th><th>Mandatory</th><th>Description</th><#if includeDataSource><th>Data source</th></#if></tr></thead>
<tbody>
<#list label.properties as p>
<tr><td><code>${p.name?html}</code></td><td>${p.types?join(", ")?html}</td><td>${p.nullable?then("no","yes")}</td><td>${p.description?html}</td><#if includeDataSource><td>${(p.dataSource!"")?html}</td></#if></tr>
</#list>
</tbody>
</table>
</#if>
</#list>
</#if>

<#if doc.relationshipTypes?has_content>
<h2>Relationship Types</h2>
<#list doc.relationshipTypes as rel>
<#assign viewNames = relMembership[rel.name]![] />
<h3><code>${rel.name?html}</code></h3>
<p>${rel.count} relationships<#if viewNames?has_content> | <em>Appears in: ${viewNames?join(", ")?html}</em></#if></p>
<#if rel.connections?has_content>
<ul>
<#list rel.connections as conn>
<li><code>(:<#list conn.startLabels as l>${l?html}<#sep> | :</#sep></#list>)-[:${rel.name?html}]->(:<#list conn.endLabels as l>${l?html}<#sep> | :</#sep></#list>)</code> — ${conn.count}</li>
</#list>
</ul>
</#if>
<#if rel.dataSource?has_content && includeDataSource>
<p><em>Source: ${rel.dataSource?html}</em></p>
</#if>
<#if rel.description?has_content>
<p>${rel.description?html}</p>
</#if>
<#if rel.properties?has_content>
<table>
<thead><tr><th>Property</th><th>Type(s)</th><th>Mandatory</th><th>Description</th><#if includeDataSource><th>Data source</th></#if></tr></thead>
<tbody>
<#list rel.properties as p>
<tr><td><code>${p.name?html}</code></td><td>${p.types?join(", ")?html}</td><td>${p.nullable?then("no","yes")}</td><td>${p.description?html}</td><#if includeDataSource><td>${(p.dataSource!"")?html}</td></#if></tr>
</#list>
</tbody>
</table>
</#if>
</#list>
</#if>

<hr>
<h2>Indexes</h2>
<#if doc.indexes?has_content>
<table>
<thead><tr><th>Name</th><th>Type</th><th>Entity</th><th>Labels / Types</th><th>Properties</th><th>Read count</th><th>Options</th></tr></thead>
<tbody>
<#list doc.indexes as idx>
<tr><td><code>${idx.name?html}</code></td><td>${idx.type?html}</td><td>${idx.entityType?html}</td><td>${idx.labelsOrTypes?join(", ")?html}</td><td>${idx.properties?join(", ")?html}</td><td>${idx.readCount}</td><td>${(idx.indexConfig!"")?html}</td></tr>
</#list>
</tbody>
</table>
<#else>
<p>No indexes collected.</p>
</#if>

<h2>Constraints</h2>
<#if doc.constraints?has_content>
<table>
<thead><tr><th>Name</th><th>Type</th><th>Entity</th><th>Labels / Types</th><th>Properties</th></tr></thead>
<tbody>
<#list doc.constraints as c>
<tr><td><code>${c.name?html}</code></td><td>${c.type?html}</td><td>${c.entityType?html}</td><td>${c.labelsOrTypes?join(", ")?html}</td><td>${c.properties?join(", ")?html}</td></tr>
</#list>
</tbody>
</table>
<#else>
<p>No constraints collected.</p>
</#if>

</body>
</html>
