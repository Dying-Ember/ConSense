const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const { webcrypto } = require('node:crypto')
const { JSDOM } = require('jsdom')
const { createRequire } = require('node:module')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const JSZip = createRequire(require.resolve('mammoth/package.json'))('jszip')
const window = new JSDOM('').window
const { convertDocumentReading } = loadTypeScript(path.join(__dirname, '../src/drafting/document-reading.ts'), {
  globals: { crypto: webcrypto, document: window.document, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter },
  dependencies: { jszip: { default: JSZip }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, dompurify: { default: require('dompurify')(window) }, './document-reading-text': loadTypeScript(path.join(__dirname, '../src/drafting/document-reading-text.ts')) }
})

async function docx(body, extraParts = {}) {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>${body}</w:body></w:document>`)
  for (const [name, value] of Object.entries(extraParts)) zip.file(name, value)
  return zip.generateAsync({ type: 'arraybuffer' })
}
async function hash(bytes) {
  return Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex')
}

async function main() {
  // Invalid ZIP bytes with a wrong revision hash must be rejected by the identity boundary.
  await assert.rejects(convertDocumentReading(new Uint8Array([1, 2, 3]).buffer, '0'.repeat(64)), /DOCX_VERSION_MISMATCH/)
  console.log('PASS: mismatched revision bytes are rejected before document conversion')

  const bytes = await docx('<w:p><w:r><w:t>Repeated clause</w:t></w:r></w:p><w:p/><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Repeated clause</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Amount</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>End of full document</w:t></w:r></w:p>')
  const unchanged = Buffer.from(bytes)
  const result = await convertDocumentReading(bytes, await hash(bytes))
  const paths = [
    'word/document.xml#/w:document[1]/w:body[1]/w:p[1]',
    'word/document.xml#/w:document[1]/w:body[1]/w:p[2]',
    'word/document.xml#/w:document[1]/w:body[1]/w:tbl[1]/w:tr[1]/w:tc[1]/w:p[1]',
    'word/document.xml#/w:document[1]/w:body[1]/w:tbl[1]/w:tr[1]/w:tc[2]/w:p[1]',
    'word/document.xml#/w:document[1]/w:body[1]/w:p[3]'
  ]
  assert.deepEqual(Array.from(result.paragraphs, paragraph => paragraph.path), paths)
  assert.deepEqual(Array.from(result.paragraphs, paragraph => paragraph.text), ['Repeated clause', '', 'Repeated clause', 'Amount', 'End of full document'])
  const flow = window.document.createElement('div'); flow.innerHTML = result.html
  assert.deepEqual(Array.from(flow.querySelectorAll('[data-native-paragraph]'), block => block.dataset.nativeParagraph), paths)
  assert.equal(flow.querySelectorAll('table tr td').length, 2)
  assert.equal(flow.querySelector('strong').textContent, 'Amount')
  assert.equal(result.coverage.complete, true)
  assert.equal(result.coverage.mappedParagraphCount, 5)
  assert.equal(result.docxSha256, await hash(bytes))
  assert.deepEqual(Buffer.from(bytes), unchanged)
  console.log('PASS: duplicate text, empty paragraphs and full table flow use exact XML identities without changing input bytes')

  const formattedBytes = await docx('<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Full source heading</w:t></w:r></w:p><w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:rPr><w:i/><w:u w:val="single"/></w:rPr><w:t>Listed text</w:t></w:r></w:p><w:p><w:bookmarkStart w:id="0" w:name="untrusted-source-id"/><w:bookmarkEnd w:id="0"/><w:hyperlink r:id="evil"><w:r><w:t>Unsafe link text</w:t></w:r></w:hyperlink><w:r><w:t xml:space="preserve"> &lt;script&gt;alert(1)&lt;/script&gt;</w:t><w:tab/><w:t>tabbed</w:t><w:br/><w:t>new line</w:t></w:r></w:p>', {
    'word/styles.xml': '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>',
    'word/numbering.xml': '<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>',
    'word/_rels/document.xml.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="evil" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="javascript:alert(1)" TargetMode="External"/></Relationships>'
  })
  const formatted = await convertDocumentReading(formattedBytes, await hash(formattedBytes))
  flow.innerHTML = formatted.html
  assert.equal(flow.querySelector('h1').textContent, 'Full source heading')
  assert.equal(flow.querySelector('ol li').textContent, 'Listed text')
  assert.equal(flow.querySelector('u em,em u').textContent, 'Listed text')
  assert.equal(flow.querySelectorAll('script,[href],[id],[style],[onclick]').length, 0)
  assert.equal(flow.querySelectorAll('[data-native-paragraph]').length, 3)
  assert.equal(formatted.paragraphs[2].text, 'Unsafe link text <script>alert(1)</script>\ttabbed\nnew line')
  assert.equal(formatted.coverage.complete, true)
  assert.equal(formatted.coverage.numberedParagraphCount, 1)
  assert.equal(formatted.coverage.numberingLabelsVerified, false, 'Paragraph/text receipts do not establish native list-label or continuation fidelity')
  assert.ok(formatted.warnings.some(warning => /Native numbering labels.*not verified/.test(warning)))
  console.log('PASS: headings, lists and inline formatting survive while source links/bookmarks cannot inject active HTML or native identities')

  const inheritedStyles = '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="DefaultList"><w:name w:val="Default list"/><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style><w:style w:type="paragraph" w:styleId="BaseList"><w:name w:val="Base list"/><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style><w:style w:type="paragraph" w:styleId="ChildList"><w:name w:val="Inherited list"/><w:basedOn w:val="BaseList"/></w:style><w:style w:type="paragraph" w:styleId="DirectList"><w:name w:val="Direct style list"/><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Plain"><w:name w:val="Plain"/></w:style><w:style w:type="paragraph" w:styleId="Suppressed"><w:name w:val="Suppressed list"/><w:basedOn w:val="BaseList"/><w:pPr><w:numPr><w:numId w:val="0"/></w:numPr></w:pPr></w:style><w:style w:type="paragraph" w:styleId="CycleA"><w:name w:val="Cycle A"/><w:basedOn w:val="CycleB"/></w:style><w:style w:type="paragraph" w:styleId="CycleB"><w:name w:val="Cycle B"/><w:basedOn w:val="CycleA"/></w:style><w:style w:type="paragraph" w:styleId="NumberCycleA"><w:name w:val="Number cycle A"/><w:basedOn w:val="NumberCycleB"/></w:style><w:style w:type="paragraph" w:styleId="NumberCycleB"><w:name w:val="Number cycle B"/><w:basedOn w:val="NumberCycleA"/><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style><w:style w:type="paragraph" w:styleId="UnusedList"><w:name w:val="Unused list"/><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style></w:styles>'
  const styledParagraph = (style, text, disabled = false) => `<w:p>${style || disabled ? `<w:pPr>${style ? `<w:pStyle w:val="${style}"/>` : ''}${disabled ? '<w:numPr><w:numId w:val="0"/></w:numPr>' : ''}</w:pPr>` : ''}<w:r><w:t>${text}</w:t></w:r></w:p>`
  const inheritedBytes = await docx(styledParagraph('ChildList', 'Inherited through basedOn') + styledParagraph('DirectList', 'Direct used-style numbering') + styledParagraph('Plain', 'Plain used style') + styledParagraph('Suppressed', 'Style explicitly disables numbering') + styledParagraph('ChildList', 'Paragraph explicitly disables numbering', true) + styledParagraph('CycleA', 'Unnumbered cyclic style') + styledParagraph('NumberCycleA', 'Numbered cyclic style') + styledParagraph('Plain', 'Unused numbering style is irrelevant') + styledParagraph('', 'Default style numbering'), { 'word/styles.xml': inheritedStyles })
  const inherited = await convertDocumentReading(inheritedBytes, await hash(inheritedBytes))
  assert.equal(inherited.coverage.numberedParagraphCount, 4, 'Only used/default style chains and direct numbering count; explicit numId=0 overrides inherited numbering')
  assert.equal(inherited.coverage.numberingLabelsVerified, false)
  assert.ok(inherited.warnings.some(warning => /Native numbering labels.*not verified/.test(warning)))
  assert.equal(inherited.coverage.complete, true); assert.equal(inherited.coverage.mappedParagraphCount, 9)
  assert.equal(inherited.paragraphs[0].text, 'Inherited through basedOn'); assert.equal(inherited.paragraphs[8].text, 'Default style numbering')
  console.log('PASS: used/default paragraph-style numbering and basedOn chains remain explicit, with disabled and cyclic styles bounded without rewriting labels')

  const mathBytes = await docx('<w:p><w:r><w:t xml:space="preserve">Rate = </w:t></w:r><m:oMath><m:f><m:num><m:r><m:t>A</m:t></m:r></m:num><m:den><m:sSub><m:e><m:r><m:t>B</m:t></m:r></m:e><m:sub><m:r><m:t>0</m:t></m:r></m:sub></m:sSub></m:den></m:f></m:oMath></w:p><w:p><m:oMathPara><m:oMath><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:oMath></m:oMathPara></w:p>')
  const math = await convertDocumentReading(mathBytes, await hash(mathBytes))
  flow.innerHTML = math.html
  assert.equal(math.paragraphs[0].text, 'Rate = ')
  assert.equal(math.paragraphs[0].htmlText, 'Rate = (A) / (B0)')
  assert.equal(math.paragraphs[1].htmlText, 'x2')
  assert.equal(flow.querySelector('sub').textContent, '0')
  assert.equal(flow.querySelector('sup').textContent, '2')
  assert.equal(flow.querySelectorAll('[data-native-math]').length, 2)
  assert.equal(math.coverage.officeMathCount, 2)
  assert.equal(math.coverage.renderedOfficeMathCount, 2)
  assert.equal(math.coverage.complete, true)
  console.log('PASS: inline and display Office Math retain fractions and scripts without corrupting native paragraph text')

  const mergedBytes = await docx('<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>Merged title</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>First row</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge/></w:tcPr><w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="100"/></w:tabs></w:pPr></w:p></w:tc><w:tc><w:p><w:r><w:t>Second row</w:t></w:r></w:p></w:tc></w:tr></w:tbl>')
  const merged = await convertDocumentReading(mergedBytes, await hash(mergedBytes))
  flow.innerHTML = merged.html
  assert.equal(merged.paragraphs[2].text, '')
  assert.equal(merged.coverage.complete, true)
  assert.equal(merged.coverage.mappedParagraphCount, 4)
  assert.equal(merged.coverage.hiddenMergedCellParagraphs.length, 1)
  const hidden = flow.querySelector('[data-native-merged-continuation]')
  assert.equal(hidden.dataset.nativeParagraph, 'word/document.xml#/w:document[1]/w:body[1]/w:tbl[1]/w:tr[2]/w:tc[1]/w:p[1]')
  assert.equal(hidden.hidden, true)
  assert.equal(hidden.getAttribute('aria-hidden'), 'true')
  assert.equal(hidden.closest('td').rowSpan, 2)
  assert.equal(hidden.closest('td').colSpan, 2)
  assert.equal(merged.coverage.nonemptyParagraphOrderVerified, true)
  console.log('PASS: empty vertical-merge continuation paragraphs retain identities inside their actual spanning cells')

  const unsupportedBytes = await docx('<w:p><m:oMath><m:rad><m:radPr><m:degHide m:val="1"/></m:radPr><m:e><m:r><m:t>Original formula atom</m:t></m:r></m:e></m:rad></m:oMath></w:p>')
  const unsupported = await convertDocumentReading(unsupportedBytes, await hash(unsupportedBytes))
  assert.equal(unsupported.paragraphs[0].htmlText, 'Original formula atom')
  assert.equal(unsupported.coverage.officeMathCount, 1)
  assert.equal(unsupported.coverage.renderedOfficeMathCount, 1)
  assert.equal(unsupported.coverage.complete, false)
  assert.ok(unsupported.warnings.some(warning => /Office Math.*rad/.test(warning)))
  console.log('PASS: unsupported formula structures retain their literal atoms and report an explicit coverage limitation')

  const imageBytes = await docx('<w:p><w:r><w:drawing><wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><wp:extent cx="9525" cy="9525"/><wp:docPr id="1" name="Real inline PNG"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="1" name="dot.png"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="inlineImage"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="9525" cy="9525"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing><w:t>Actual caption remains readable</w:t></w:r></w:p><w:p><w:r><w:t>Actual following body remains readable</w:t></w:r></w:p>', {
    '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    'word/_rels/document.xml.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="inlineImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/dot.png"/></Relationships>',
    'word/media/dot.png': Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=', 'base64')
  })
  const imageReading = await convertDocumentReading(imageBytes, await hash(imageBytes))
  assert.equal(imageReading.coverage.complete, false, 'Stripped native drawing cannot be reported as complete document coverage')
  assert.equal(imageReading.coverage.omittedNativeGraphics.length, 1)
  assert.equal(imageReading.coverage.omittedNativeGraphics[0].id, 'word/document.xml#/w:document[1]/w:body[1]/w:p[1]/w:r[1]/w:drawing[1]')
  assert.ok(imageReading.warnings.some(warning => /native drawings\/images omitted/i.test(warning)))
  flow.innerHTML = imageReading.html; assert.match(flow.textContent, /Actual caption remains readable/); assert.match(flow.textContent, /Actual following body remains readable/)
  assert.equal(imageReading.coverage.mappedParagraphCount, 2)
  console.log('PASS: omitted embedded PNG is reported by exact native identity without losing its actual caption or body')

  // Optional immutable acceptance inputs live outside the repo; runtime code never reads fixtures.
  const fixtureOption = process.argv.indexOf('--fixture-directory')
  if (fixtureOption >= 0) {
    const fixtureDirectory = process.argv[fixtureOption + 1]
    assert.ok(fixtureDirectory, '--fixture-directory requires a directory')
    const actualDocuments = [
      ['NTT-original', '60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c', 665, 7, 0],
      ['NTT-changed', '04fdcdcfb58e5ad28920a9ae17c1f3961aa97132a6338d5cbc3e7bb0c257d5ea', 672, 7, 0],
      ['SCT-original', '7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4', 1238, 6, 0],
      ['SCT-changed', '0b41703f654fa79cd3ea5846cf702d9f96a5241814cd61c0a3358881a3678f37', 1027, 6, 0],
      ['SCC-original', '9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208', 2074, 6, 4],
      ['SCC-changed', '44c7e5767fc8779be6628a36a2a3f6564bb8c2b609ede2b5e41177338cabd388', 2041, 6, 4]
    ]
    for (const [name, sha256, paragraphCount, mergedCount, mathCount] of actualDocuments) {
      const buffer = fs.readFileSync(path.join(fixtureDirectory, `${name}.docx`))
      const input = buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength)
      const reading = await convertDocumentReading(input, sha256)
      assert.equal(reading.docxSha256, sha256)
      assert.equal(reading.paragraphs.length, paragraphCount)
      assert.equal(reading.coverage.complete, true, `${name}: ${JSON.stringify(reading.coverage)}`)
      assert.equal(reading.coverage.mappedParagraphCount, paragraphCount)
      assert.equal(reading.coverage.normalizedTextMatchCount, paragraphCount)
      assert.equal(reading.coverage.hiddenMergedCellParagraphs.length, mergedCount)
      assert.equal(reading.coverage.officeMathCount, mathCount)
      assert.equal(reading.coverage.renderedOfficeMathCount, mathCount)
      assert.equal(reading.coverage.nonemptyParagraphOrderVerified, true)
      assert.deepEqual(Buffer.from(input), buffer)
      console.log(`PASS: ${name}: ${paragraphCount}/${paragraphCount} native paragraphs/texts; ${mergedCount} merged empty identities; ${mathCount} formulas; complete order`)
    }
  }
}
main().then(() => window.close()).catch(error => { console.error(error); window.close(); process.exitCode = 1 })
