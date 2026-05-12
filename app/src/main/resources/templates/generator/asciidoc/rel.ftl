=== ${rel.displayName}

${rel.count} relationships<#if viewNames?has_content> | _Appears in: ${viewNames?join(", ")}_</#if>

<#if rel.connections?has_content>
<#list rel.connections as conn>
* `(:<#list conn.startLabels as l>${l}<#sep> | :</#sep></#list>)-[:${rel.name}]->(:<#list conn.endLabels as l>${l}<#sep> | :</#sep></#list>)` — ${conn.count}
</#list>

</#if>
<#if rel.dataSource?has_content && includeDataSource>
_Source: ${rel.dataSource}_

</#if>
<#if rel.description?has_content>
${rel.description}

</#if>
<#if rel.parameterized>
<#assign shownCount = [rel.instances?size, 5]?min>
NOTE: Parameterised type. Raw type names encode runtime metadata in the suffix. Representative names: `${rel.instances[0..<shownCount]?join("`, `")}`<#if rel.instances?size gt 5> (+${rel.instances?size - 5} more)</#if>

[%header,cols="1,2,3,3"]
|===
|Slot|Name|Description|Example values

<#list rel.typeParameters as param>
|${param.position + 1}|`${param.name}`|${param.description}|`${param.exampleValues?join("`, `")}`
</#list>
|===

</#if>
<#if rel.properties?has_content>
[%header,cols="2,2,1,4<#if includeDataSource>,3</#if>"]
|===
|Property|Type(s)|Mandatory|Description<#if includeDataSource>|Data source</#if>

<#list rel.properties as p>
|`${p.name}`|${p.types?join(", ")}|${p.nullable?then("no","yes")}|${p.description}<#if includeDataSource>|${p.dataSource!""}</#if>
</#list>
|===

</#if>
