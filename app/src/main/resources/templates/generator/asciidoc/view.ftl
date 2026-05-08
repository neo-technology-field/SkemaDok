=== ${view.name}

<#if imgPath?has_content>
image::${imgPath}[${view.name}]

</#if>
<#if view.description?has_content>
${view.description}

</#if>
