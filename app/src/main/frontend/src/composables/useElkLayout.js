import ELK from 'elkjs/lib/elk.bundled.js'

const elk = new ELK()

const ALGO_OPTIONS = {
    force: {
        'elk.algorithm': 'force',
        'elk.force.iterations': '200',
    },
    layered: {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.layered.spacing.nodeNodeBetweenLayers': '150',
    },
    stress: {
        'elk.algorithm': 'stress',
        'elk.stress.desiredEdgeLength': '300',
    },
    tree: {
        'elk.algorithm': 'mrtree',
        'elk.mrtree.searchOrder': 'DFS',
    },
}

/**
 * Runs an ELK layout algorithm over the supplied nodes and edges.
 *
 * Self-loops are stripped before submission — ELK cannot route them and they
 * carry no information for rank assignment. Edge routing is intentionally
 * disabled so the result is pure node positions; PolylineEdge handles routing.
 *
 * @param {Array<{id: string}>} nodes
 * @param {Array<{id: string, source: string, target: string}>} edges
 * @param {Object<string, {width: number, height: number}>} dimensions
 * @param {string} algorithm  One of: 'force', 'layered', 'stress'
 * @returns {Promise<Object<string, {x: number, y: number}>>}
 */
export async function computeElkLayout(nodes, edges, dimensions, algorithm = 'force') {
    const elkGraph = {
        id: 'root',
        layoutOptions: {
            'elk.spacing.nodeNode': '80',
            'elk.edgeRouting': 'NONE',
            ...(ALGO_OPTIONS[algorithm] ?? ALGO_OPTIONS.force),
        },
        children: nodes.map(n => ({
            id:     n.id,
            width:  dimensions[n.id]?.width  ?? 220,
            height: dimensions[n.id]?.height ?? 80,
        })),
        edges: edges
            .filter(e => e.source !== e.target)
            .map(e => ({
                id:      e.id,
                sources: [e.source],
                targets: [e.target],
            })),
    }

    const result = await elk.layout(elkGraph)

    return Object.fromEntries(
        result.children.map(n => [n.id, { x: Math.round(n.x), y: Math.round(n.y) }])
    )
}
