import type { BusinessGraphEdge } from './business-graph'

/** Native diagram units are pixels at the initial 100% scale. */
export const businessGraphGeometry = {
  width: 1080, minHeight: 430, bottom: 70,
  group: { x: 8, w: 244 },
  input: { x: 26, y: 48, w: 214, h: 54, row: 68 },
  subfield: { x: 44, w: 196, h: 34, row: 42 },
  action: { x: 432, y: 96, w: 210, h: 52, row: 68 },
  file: { x: 896, y: 50, w: 154, h: 76 }
}

export interface GraphNodeBox { x: number; y: number; w: number; h: number }

/**
 * Route against the complete eligible relation set, not the selected chain.
 * Hidden/filter-excluded endpoints retain their port reservations, so appending
 * related context cannot shift the pool's existing fan-out or fan-in paths.
 */
export function routeBusinessGraphEdges(edges: BusinessGraphEdge[], position: (id: string) => GraphNodeBox | undefined): Map<string, string> {
  const outgoing = new Map<string, BusinessGraphEdge[]>(), incoming = new Map<string, BusinessGraphEdge[]>()
  const side = (edge: BusinessGraphEdge) => edge.kind === 'prerequisite' ? 'left' : 'right'
  for (const edge of edges.filter(item => item.kind !== 'structure')) {
    const from = `${side(edge)}:${edge.from}`, to = `${side(edge)}:${edge.to}`
    outgoing.set(from, [...(outgoing.get(from) ?? []), edge]); incoming.set(to, [...(incoming.get(to) ?? []), edge])
  }
  const slot = (edge: BusinessGraphEdge, endpoint: string, buckets: Map<string, BusinessGraphEdge[]>) => {
    const list = buckets.get(`${side(edge)}:${endpoint}`) ?? [edge], index = list.findIndex(item => item.id === edge.id)
    return { port: (index + 1) / (list.length + 1), lane: list.length <= 1 ? .5 : index / (list.length - 1) }
  }
  const coordinate = (value: number) => Number(value.toFixed(4))
  const portY = (box: GraphNodeBox, fraction: number) => box.y + 8 + (box.h - 16) * fraction
  const potentialEdges = edges.filter(edge => edge.kind === 'potential'), hasActionColumn = edges.some(edge => edge.kind === 'destination')
  const routes = new Map<string, string>()
  for (const edge of edges) {
    const a = position(edge.from), b = position(edge.to)
    if (!a || !b) continue
    if (edge.kind === 'structure') {
      routes.set(edge.id, `M${a.x + 10},${a.y + a.h} V${b.y + b.h / 2} H${b.x}`)
      continue
    }
    const source = slot(edge, edge.from, outgoing), target = slot(edge, edge.to, incoming)
    const y = coordinate(portY(a, source.port)), by = coordinate(portY(b, target.port))
    if (edge.kind === 'prerequisite') {
      routes.set(edge.id, `M${a.x},${y} C${coordinate(2 + source.lane * 6)},${y} ${coordinate(2 + target.lane * 6)},${by} ${b.x},${by}`)
      continue
    }
    const x = a.x + a.w, gap = b.x - x
    if (edge.kind === 'potential' && hasActionColumn) {
      // A catalogue target skips the action column. Cross it only in the
      // reserved top band, with vertical approaches inside the two gutters.
      const column = businessGraphGeometry.action
      const leftLane = coordinate(x + (column.x - x) * (.2 + source.lane * .5))
      const rightLane = coordinate(column.x + column.w + (b.x - column.x - column.w) * (.3 + target.lane * .4))
      const index = potentialEdges.findIndex(item => item.id === edge.id)
      const topLane = coordinate(32 + (column.y - 44) * (index + 1) / (potentialEdges.length + 1))
      routes.set(edge.id, `M${x},${y} C${leftLane},${y} ${leftLane},${topLane} ${leftLane},${topLane} L${rightLane},${topLane} C${rightLane},${topLane} ${rightLane},${by} ${b.x},${by}`)
      continue
    }
    const firstLane = coordinate(x + gap * (.22 + source.lane * .18)), lastLane = coordinate(b.x - gap * (.22 + target.lane * .18))
    routes.set(edge.id, `M${x},${y} C${firstLane},${y} ${lastLane},${by} ${b.x},${by}`)
  }
  return routes
}
