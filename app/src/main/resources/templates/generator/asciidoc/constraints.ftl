[appendix]
== Indexes

<#if doc.indexes?has_content>
[%header,cols="3,2,1,2,2,1,3"]
|===
|Name|Type|Entity|Labels / Types|Properties|Read count|Options

<#list doc.indexes as idx>
|`${idx.name}`|${idx.type}|${idx.entityType}|${idx.labelsOrTypes?join(", ")}|${idx.properties?join(", ")}|${idx.readCount}|${idx.indexConfig!""}
</#list>
|===

<#else>
No indexes collected (may require elevated privileges).

</#if>
[appendix]
== Constraints

<#if doc.constraints?has_content>
[%header,cols="3,2,1,2,2"]
|===
|Name|Type|Entity|Labels / Types|Properties

<#list doc.constraints as c>
|`${c.name}`|${c.type}|${c.entityType}|${c.labelsOrTypes?join(", ")}|${c.properties?join(", ")}
</#list>
|===

<#else>
No constraints collected (may require elevated privileges).

</#if>
