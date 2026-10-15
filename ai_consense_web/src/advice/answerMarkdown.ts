import { renderMarkdown } from '@/composables/useMarkdown'

const escape = (value: string) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
function cells(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map(cell => cell.trim().replace(/\\\|/g, '|'))
}
/** Extend the existing escaped Markdown subset with tables, literal fences and trusted citation buttons. */
export function renderAdviceMarkdown(source: string, citationIds: string[] = []): string {
  const lines = source.replace(/\r\n/g, '\n').split('\n'), blocks: string[] = [], text: string[] = []
  const flush = () => { if (text.length) blocks.push(renderMarkdown(text.splice(0).join('\n'))) }
  const cellHtml = (value: string) => renderMarkdown(value).replace(/^<p>([\s\S]*)<\/p>$/, '$1')
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index]!
    if (/^\s*```/.test(line)) {
      flush(); const code = [line]
      while (++index < lines.length) { code.push(lines[index]!); if (/^\s*```\s*$/.test(lines[index]!)) break }
      blocks.push(`<pre>${escape(code.join('\n'))}</pre>`); continue
    }
    if (line.includes('|') && index + 1 < lines.length && cells(line).length === cells(lines[index + 1]!).length && cells(lines[index + 1]!).every(cell => /^:?-{3,}:?$/.test(cell))) {
      flush(); const headers = cells(line), rows: string[][] = []
      index++
      while (index + 1 < lines.length && lines[index + 1]!.includes('|') && lines[index + 1]!.trim()) rows.push(cells(lines[++index]!))
      while (headers.length < Math.max(headers.length, ...rows.map(row => row.length))) headers.push('')
      blocks.push(`<table><thead><tr>${headers.map(header => `<th scope="col">${cellHtml(header)}</th>`).join('')}</tr></thead><tbody>${rows.map(row => `<tr>${headers.map((_, column) => `<td>${cellHtml(row[column] ?? '')}</td>`).join('')}</tr>`).join('')}</tbody></table>`)
      continue
    }
    text.push(line)
  }
  flush()
  const allowed = new Set(citationIds.filter(id => /^\[[1-9][0-9]*\]$/.test(id)))
  // HTML is generated solely by the escaped renderer. Do not replace inside tags, code or pre blocks.
  let literal = false
  return blocks.join('\n').split(/(<[^>]*>)/g).map(part => {
    if (part.startsWith('<')) { if (/^<(?:pre|code)\b/.test(part)) literal = true; if (/^<\/(?:pre|code)>/.test(part)) literal = false; return part }
    return literal ? part : part.replace(/\[[1-9][0-9]*\]/g, id => allowed.has(id)
      ? `<button type="button" class="answer-citation" data-advice-citation="${id}" aria-label="${id}">${id}</button>` : id)
  }).join('')
}
