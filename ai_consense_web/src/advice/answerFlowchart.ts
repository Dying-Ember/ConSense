export type AnswerPart = { kind: 'text' | 'flowchart'; source: string }

/** Only explicitly fenced Mermaid is executable diagram input. Other text stays literal. */
export function answerParts(answer: string): AnswerPart[] {
  const result: AnswerPart[] = []
  const fence = /^(`{3,}|~{3,})mermaid[ \t]*\r?\n([\s\S]*?)^\1[ \t]*(?:\r?\n|$)/gim
  let end = 0
  for (const match of answer.matchAll(fence)) {
    const start = match.index!
    if (start > end) result.push({ kind: 'text', source: answer.slice(end, start) })
    result.push({ kind: 'flowchart', source: match[2] })
    end = start + match[0].length
  }
  if (end < answer.length) result.push({ kind: 'text', source: answer.slice(end) })
  return result
}

let nextDiagram = 0
let renderQueue: Promise<unknown> = Promise.resolve()

/** Serial rendering avoids Mermaid's shared parser state; no model text becomes page HTML. */
export function renderAnswerFlowchart(source: string): Promise<string> {
  const render = async () => {
    // Mermaid can silently replace oversized source with an error SVG; keep our explicit failure/raw-source path.
    if (source.length > 100000) throw new Error('FLOWCHART_TOO_LARGE')
    if (!/^\s*(?:flowchart|graph)\s+(?:TB|TD|BT|RL|LR)\b/.test(source)
        || source.includes('%%{') || /^\s*---/.test(source)
        || /@\s*\{/.test(source) || /(?:^|;)\s*(?:click|classDef|style|linkStyle)\b/im.test(source)) {
      throw new Error('UNSUPPORTED_FLOWCHART')
    }
    const [{ default: mermaid }, { default: purify }] = await Promise.all([import('mermaid'), import('dompurify')])
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, htmlLabels: false,
      maxTextSize: 100000, maxEdges: 2048, flowchart: { htmlLabels: false } })
    const id = `advice-flowchart-${++nextDiagram}`
    try {
      const { svg } = await mermaid.render(id, source)
      // Drop all active content, links and CSS from generated SVG. Local component CSS supplies appearance.
      const safeSvg = purify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: false }, RETURN_DOM_FRAGMENT: true,
        FORBID_TAGS: ['script', 'style', 'foreignObject', 'a', 'image', 'use', 'animate', 'set'],
        FORBID_ATTR: ['style', 'href', 'xlink:href'] })
      const diagram = safeSvg.querySelector('svg')
      if (!diagram) throw new Error('FLOWCHART_RENDER_FAILED')
      // Mermaid leaves these escaped label characters as literal entities when htmlLabels is false.
      const textNodes = safeSvg.ownerDocument.createTreeWalker(safeSvg, 4 /* SHOW_TEXT */)
      while (textNodes.nextNode()) {
        const node = textNodes.currentNode
        node.nodeValue = node.nodeValue?.replace(/&#38;|&#35;|&quot;/g, entity =>
          entity === '&#38;' ? '&' : entity === '&#35;' ? '#' : '"') ?? null
      }
      return diagram.outerHTML
    } finally {
      // Mermaid inserts a temporary container even when parsing fails.
      if (typeof document !== 'undefined') document.getElementById(`d${id}`)?.remove()
    }
  }
  const result = renderQueue.then(render, render)
  renderQueue = result.catch(() => undefined)
  return result
}
