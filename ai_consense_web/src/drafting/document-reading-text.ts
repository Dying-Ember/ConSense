/** Native reading text and DOM boundaries share one walk. A BR is a real
 * paragraph newline, with its boundary in the parent rather than a Text node. */
export interface ParagraphTextRun { text: string; node: Node; startOffset: number; endOffset: number; isText: boolean }

export function walkParagraphText(element: Element, visit: (run: ParagraphTextRun) => void, excludeOfficeMath = false): void {
  function walk(node: Node) {
    if (node.nodeType === 3) {
      const text = node.textContent ?? ''
      visit({ text, node, startOffset: 0, endOffset: text.length, isText: true }); return
    }
    if (node.nodeType !== 1) return
    const child = node as Element
    if (excludeOfficeMath && child.hasAttribute('data-native-math')) return
    if (child.tagName === 'BR') {
      const parent = node.parentNode
      if (parent) {
        const index = Array.from(parent.childNodes).indexOf(node as ChildNode)
        visit({ text: '\n', node: parent, startOffset: index, endOffset: index + 1, isText: false })
      }
      return
    }
    for (const descendant of Array.from(node.childNodes)) walk(descendant)
  }
  walk(element)
}

export function documentParagraphText(element: Element, excludeOfficeMath = false): string {
  let text = ''
  walkParagraphText(element, run => { text += run.text }, excludeOfficeMath)
  return text
}
