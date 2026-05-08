const BG_COLOURS = { light: '#ffffff', dark: '#0d1117' }

/**
 * Captures a Vue Flow canvas element as a PNG data URL.
 *
 * SchemaGraph instances must already be mounted in the DOM (typically as
 * offscreen hidden elements inside GenerateView). Temporarily applies the
 * requested theme so captured images are independent of the user's current
 * UI theme — documentation typically requires light backgrounds.
 *
 * @param {HTMLElement} container - wrapper element containing a rendered SchemaGraph
 * @param {'light'|'dark'} theme - theme to apply during capture (default: 'light')
 * @returns {Promise<string>} PNG data URL
 */
export async function captureContainerAsPng(container, theme = 'light') {
    const { toPng } = await import('html-to-image')
    const flowEl = container.querySelector('.vue-flow')
    if (!flowEl) {
        throw new Error('Vue Flow element not found — view may not have finished rendering')
    }

    const root         = document.documentElement
    const previousTheme = root.dataset.theme

    // Inline display:none is the most reliable way to suppress SVG elements in
    // html-to-image — it reads window.getComputedStyle() on the live DOM so inline
    // style wins regardless of CSS cascade or filter callback behaviour.
    const handles = Array.from(flowEl.querySelectorAll('.waypoint-handle, .midpoint-handle'))
    handles.forEach(h => { h.style.display = 'none' })
    flowEl.classList.add('capturing')
    root.dataset.theme = theme
    try {
        return await toPng(flowEl, {
            width:           1200,
            height:          900,
            backgroundColor: BG_COLOURS[theme] ?? BG_COLOURS.light,
            skipFonts:       false,
        })
    } finally {
        handles.forEach(h => { h.style.display = '' })
        flowEl.classList.remove('capturing')
        root.dataset.theme = previousTheme
    }
}
