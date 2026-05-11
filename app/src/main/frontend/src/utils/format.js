/**
 * Builds the display name for a relationship type, encoding variable slots.
 * Plain types return the name unchanged; parameterised types return e.g. WORKS_FOR_{year}_{quarter}.
 */
export function relDisplayName(rel) {
  if (!rel?.typeParameters?.length) return rel?.name ?? ''
  return rel.name + rel.typeParameters.map(p => `_{${p.name ?? 'v' + (p.position + 1)}}`).join('')
}

/**
 * Compact count formatter for node and relationship counts.
 * Boundaries are chosen so Math.round never overflows into the next unit
 * (e.g. 999,500 → "1M", not "1000k").
 */
export function formatCount(n) {
  if (n == null) return ''
  if (n < 1_000)       return String(n)
  if (n < 10_000)      return (n / 1_000).toFixed(1).replace(/\.0$/, '') + 'k'
  if (n < 999_500)     return Math.round(n / 1_000) + 'k'
  if (n < 10_000_000)  return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M'
  if (n < 999_500_000) return Math.round(n / 1_000_000) + 'M'
  return (n / 1_000_000_000).toFixed(1).replace(/\.0$/, '') + 'B'
}
