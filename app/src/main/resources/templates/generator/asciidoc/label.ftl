=== ${label.name}

Role: `${label.role!"ENTITY"}` — ${label.nodeCount} nodes<#if viewNames?has_content> | _Appears in: ${viewNames?join(", ")}_</#if>

<#if label.dataSource?has_content && includeDataSource>
_Source: ${label.dataSource}_

</#if>
<#if label.description?has_content>
${label.description}

</#if>
<#if label.properties?has_content>
[%header,cols="2,2,1,4<#if includeDataSource>,3</#if>"]
|===
|Property|Type(s)|Mandatory|Description<#if includeDataSource>|Data source</#if>

<#list label.properties as p>
|`${p.name}`|${p.types?join(", ")}|${p.nullable?then("no","yes")}|${p.description}<#if includeDataSource>|${p.dataSource!""}</#if>
</#list>
|===

</#if>
