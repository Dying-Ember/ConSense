const assert=require('node:assert/strict'),path=require('node:path')
const {loadTypeScript}=require('./helpers/load-typescript.cjs')
const {bindPageSelection,wordsInBox}=loadTypeScript(path.join(__dirname,'../src/drafting/source-page-selection.ts'),{dependencies:{'./pdf-text-ranges':loadTypeScript(path.join(__dirname,'../src/drafting/pdf-text-ranges.ts')),'./source-review-state':loadTypeScript(path.join(__dirname,'../src/drafting/source-review-state.ts'))}})
const sha='a'.repeat(64)
const reading={id:'41',format:'docx',canEdit:true,sourceSha256:sha,paragraphs:[{id:'p1',text:'Use\tAppendix G1a with a reduced bond.',sourceSha256:sha,paragraphSha256:'b'.repeat(64)}]}
const context='Use Appendix G1a with a reduced bond.'
const word=(text,start)=>({itemIndex:0,start,end:start+text.length,text,context,rectangle:{x:start*5,y:10,width:text.length*5,height:10}})
const words=[word('Appendix',4),word('G1a',13)]
const receipt={source:'blob:one',page:2,words}
const result=bindPageSelection(reading,receipt)
assert.equal(result.native.spans[0].expectedText,'Appendix G1a');assert.equal(result.native.spans[0].start,4)
assert.equal(result.docId,'41');assert.equal(result.page,2)
assert.equal(bindPageSelection({...reading,paragraphs:[...reading.paragraphs,{...reading.paragraphs[0],id:'p2'}]},receipt).native,undefined,'Ambiguous text remains a reviewable page selection without authorizing an edit')
assert.throws(()=>bindPageSelection({...reading,sourceSha256:'wrong'},receipt),/Source selection changed/)
assert.throws(()=>bindPageSelection(reading,{...receipt,words:[{...words[0],text:'invented'}]}),/selection changed/)
const sparse=bindPageSelection(reading,{...receipt,words:[word('Use',0),word('bond.',context.indexOf('bond.'))]})
assert.equal(sparse.native.spans.length,2,'A sparse selection never expands to the skipped words')
assert.equal(bindPageSelection({...reading,format:'pdf',canEdit:false},receipt).native,undefined,'PDF source does not acquire Word editing authority')
assert.throws(()=>bindPageSelection({...reading,format:'pdf',canEdit:false},{...receipt,words:[{...words[0],text:'invented'}]}),/selection changed/)
const fragments={source:'blob:one',page:2,words:[{...word('Appendix',4),context:'Appendix',start:0,end:8,itemIndex:0},{...word('G1a',13),context:'G1a',start:0,end:3,itemIndex:1}]}
assert.equal(bindPageSelection(reading,fragments).native.spans[0].expectedText,'Appendix G1a','Complete exact selection joins split PDF print runs without guessing individual repeated fragments')
assert.deepEqual(Array.from(wordsInBox(words,{x:15,y:5,width:70,height:20})),[0,1])
console.log('PASS: exact Word context, native offsets, sparse ranges, ambiguous context and source drift guards')

const sourceSentence='(a)\tThe tender “booklet” is issued in electronic file format as detailed in Condition SCT1(2)(a).'
const printedSentence=sourceSentence.replace('as detailed','a detailed')
const clipped={...reading,paragraphs:[{...reading.paragraphs[0],text:sourceSentence}]}
const clippedReceipt={source:'blob:one',page:3,words:[{itemIndex:0,start:0,end:printedSentence.length,text:printedSentence,context:printedSentence,rectangle:{x:117,y:438,width:350,height:12}}]}
const retained=bindPageSelection(clipped,clippedReceipt)
assert.equal(retained.text,printedSentence);assert.equal(retained.page,3);assert.equal(retained.native,undefined,'A real PDF/Word text discrepancy keeps manual review available')
