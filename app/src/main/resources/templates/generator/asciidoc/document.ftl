= ${doc.databaseName} — Schema Documentation
:toc: left
:toclevels: 3
:icons: font
:source-highlighter: highlight.js

Database: `${doc.databaseAddress}` / `${doc.databaseName}`

Captured: ${capturedAt}

<#if doc.views?has_content>
== Views

<#list doc.views as view>
<#assign imgPath = imagePaths[view.name]!'' />
<#include "view.ftl">
</#list>
</#if>
<#if doc.nodeLabels?has_content>
== Node Labels

<#list doc.nodeLabels as label>
<#assign viewNames = labelMembership[label.name]![] />
<#include "label.ftl">
</#list>
</#if>
<#if doc.relationshipTypes?has_content>
== Relationship Types

<#list doc.relationshipTypes as rel>
<#assign viewNames = relMembership[rel.name]![] />
<#include "rel.ftl">
</#list>
</#if>
<#include "constraints.ftl">
