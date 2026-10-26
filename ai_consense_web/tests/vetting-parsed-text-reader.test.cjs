/* Mounted saved-text controls with fake API boundaries; no real parsing, source, or HTTP acceptance. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')
const all = (node, predicate) => [...(predicate(node) ? [node] : []), ...(node.children ?? []).flatMap(child => all(child, predicate))]
const visible = node => { for (let current=node;current;current=current.parent) if (current.props?.['data-modal-open']===false) return false; return true }
const source = extra => ({ documentId:'85', key:'APH', fileName:'APH85.pdf', sourceRole:'unknown', role:{en:'Unknown'},
  status:'PARTIAL', parseStatus:'PARTIAL', parsed:false, pageCount:85, originalSourceSha256:'a'.repeat(64), routingSha256:'b'.repeat(64),
  businessContentSha256:'c'.repeat(64), sourceHash:'d'.repeat(64), exclusionReason:'source_identity_unresolved', ...extra })
const page = (file, number, extra) => ({ documentId:file.documentId, fileName:file.fileName, parseStatus:file.parseStatus,
  originalSourceSha256:file.originalSourceSha256, routingSha256:file.routingSha256, businessContentSha256:file.businessContentSha256,
  textContentSha256:'e'.repeat(64), physicalPage:number, pageCount:85, status:'saved_text', pageNumberAuthority:'saved_parser_physical_page_markers',
  text:'AUTHORED saved Φ ≤ > text\nCondition and exception <script>remain text</script>.', semanticQuality:'unknown', canonicalAdoption:'not_performed', ...extra })
const tablePage = (file, number, extra) => ({ documentId:file.documentId, physicalPage:number,
  originalSourceSha256:file.originalSourceSha256, routingSha256:file.routingSha256, businessContentSha256:file.businessContentSha256,
  tableStatus:'no_saved_tables', tables:[], semanticQuality:'unknown', canonicalAdoption:'not_performed', ...extra })
const grid = (number=1) => ({ method:'pdfplumber_lines_lines', physicalPage:number, rowCount:3, colCount:3, continuationStatus:'unknown', cells:[
  {row:0,col:0,rowSpan:2,colSpan:1,text:'Test\nmethod'}, {row:0,col:1,rowSpan:1,colSpan:2,text:'Maximum force'},
  {row:1,col:1,rowSpan:1,colSpan:1,text:'Sliding'}, {row:1,col:2,rowSpan:1,colSpan:1,text:'Swing'},
  {row:2,col:0,rowSpan:1,colSpan:1,text:'Operation <script>literal</script>'},
  {row:2,col:1,rowSpan:1,colSpan:1,text:'≤35 N'}, {row:2,col:2,rowSpan:1,colSpan:1,text:'≤20 N'}] })
async function mount({file=source(), read, readTables, startRun, progress, controlledTimers=false, cloneFiles=false}={}) {
  const store=vue.reactive({activeProjectId:'project-a',locale:'en',notify(){},setBusy(){},clearBusy(){},captureLlmSelection:()=>({profileId:null})})
  const calls=[], tableCalls=[], pdfMounts=[], pdfUnmounts=[], state=vue.reactive({files:[file]}), empty={render:()=>null}, job={id:'run-1',status:'COMPLETED',phase:'done'}
  let fileReads=0
  const timers=[];let timerId=0
  const browserWindow=controlledTimers?{setTimeout(fn,ms){const timer={id:++timerId,fn,ms,cancelled:false};timers.push(timer);return timer.id},clearTimeout(id){const timer=timers.find(item=>item.id===id);if(timer)timer.cancelled=true}}:{setTimeout,clearTimeout}
  const loaded=loadVue(path.join(__dirname,'../src/views/VettingView.vue'),{boundaries:{
    'vue-i18n':require('vue-i18n'),'vue-router':{useRouter:()=>({push:async()=>undefined})},'@/stores/app':{useAppStore:()=>store},
    '@/composables/useLocalized':{useLocalized:()=>({currentKey:vue.ref('en'),pick:value=>value?.en??''})},'@/api/client':{ApiError:class ApiError extends Error{}},
    '@/api':{vettingApi:{files:async()=>{fileReads++;return cloneFiles?state.files.map(row=>({...row})):state.files},findings:async()=>[],metrics:async()=>({total:0}),latestRunProgress:async()=>job,getRun:async()=>job,getRunDisplay:async()=>job,
      getRunProgress:async()=>progress?progress():job,
      parsedText:async(projectId,id,pins,number)=>{calls.push({projectId,id,pins:JSON.parse(JSON.stringify(pins)),number});return read?read(file,number,pins):page(file,number)},
      parsedTables:async(projectId,id,pins,number)=>{tableCalls.push({projectId,id,pins:JSON.parse(JSON.stringify(pins)),number});return readTables?readTables(file,number):tablePage(file,number)},
      confirmSourceRole:async()=>{throw Error('No adoption')},uploadPackage:async()=>{throw Error('No parsing')},startRun:async()=>startRun?startRun():Promise.reject(Error('No review'))}},
    '@/advice/reviewHandoff':{reviewHandoffCopy:{}},'@/components/AppIcon.vue':{default:empty},
    '@/components/AppModal.vue':{default:{props:['open','title'],setup:(props,{slots,emit})=>()=>vue.h('section',{class:'test-modal',title:props.title,'data-modal-open':props.open},[slots.default?.(),vue.h('button',{'data-modal-close':true,onClick:()=>emit('close')},'Close modal')])}},
    '@/components/DraftingPdfPreview.vue':{default:{props:['source','title','locale','initialPage'],setup:props=>{
      vue.onMounted(()=>pdfMounts.push({source:props.source,title:props.title,locale:props.locale,initialPage:props.initialPage}))
      vue.onBeforeUnmount(()=>pdfUnmounts.push(props.source))
      return()=>vue.h('div',{'data-test-pdf-preview':true,'data-source':props.source,'data-initial-page':props.initialPage},'AUTHORED PDF preview boundary')
    }}},
    '@/components/CanonicalEvidencePanel.vue':{default:empty},'@/components/FixedBaselineEvidencePanel.vue':{default:empty},'@/components/AdviceSourceReader.vue':{default:empty}
  },globals:{URLSearchParams,window:browserWindow}})
  const i18n=loaded.loadLocal(path.join(__dirname,'../src/i18n/index.ts')).i18n;i18n.global.locale.value='en'
  const host=renderer(),app=host.createApp(loaded.default);app.use(i18n);app.mount(host.root);await settle()
  const button=label=>find(host.root,n=>visible(n)&&n.tag==='button'&&text(n).trim()===label)
  button(i18n.global.t('vetting.actions.source')).props.onClick();await settle()
  return {...host,calls,tableCalls,pdfMounts,pdfUnmounts,state,store,timers,button,getFileReads:()=>fileReads,findVisible:predicate=>find(host.root,n=>visible(n)&&predicate(n)),
    openModals:()=>all(host.root,n=>n.props?.['data-modal-open']===true),
    closeModal:async()=>{const current=all(host.root,n=>n.props?.['data-modal-open']===true);assert.equal(current.length,1);find(current[0],n=>n.props?.['data-modal-close']).props.onClick();await settle()},
    dispose:()=>app.unmount(),open:async()=>{button('View parsed text').props.onClick();await settle()}}
}
test('direct row actions show saved text and jump with modern pins without adopting or reparsing',async()=>{
  const h=await mount();try{
    const open=h.button('Open original'), download=h.findVisible(n=>n.tag==='a'&&text(n).trim()==='Download original')
    assert.ok(open);assert.equal(open.props.target,undefined);assert.match(download.props.href,/\/reader-original\?/);assert.ok(!download.props.href.includes('sourceHash='));assert.equal(download.props.download,'APH85.pdf');assert.equal(h.calls.length,0)
    assert.equal(h.pdfMounts.length,0,'Closed real-style modal slots must not mount the PDF component')
    open.props.onClick();await settle();assert.equal(h.openModals().length,1);assert.equal(h.pdfMounts.length,1)
    const original=new URL(h.pdfMounts[0].source,'http://localhost');assert.equal(original.pathname,'/api/vetting/project-a/sources/85/reader-original');assert.equal(original.hash,'')
    assert.deepEqual(Object.fromEntries(original.searchParams),{originalSourceSha256:'a'.repeat(64),routingSha256:'b'.repeat(64),businessContentSha256:'c'.repeat(64)})
    assert.equal(h.pdfMounts[0].initialPage,1);assert.equal(h.calls.length,0,'Opening the registered PDF does not read parsed text, parse or adopt');assert.equal(h.tableCalls.length,0)
    await h.closeModal();assert.equal(h.pdfUnmounts.length,1);assert.equal(find(h.root,n=>n.props?.['data-test-pdf-preview']),undefined)
    h.button('Review source set').props.onClick();await settle()
    const action=h.button('View parsed text');for(let parent=action.parent;parent;parent=parent.parent)assert.notEqual(parent.tag,'details')
    await h.open();assert.equal(h.calls.length,1);assert.deepEqual(h.calls[0],{projectId:'project-a',id:'85',pins:{originalSourceSha256:'a'.repeat(64),routingSha256:'b'.repeat(64),businessContentSha256:'c'.repeat(64)},number:1});assert.deepEqual(h.tableCalls,h.calls)
    const pre=find(h.root,n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text'));assert.ok(text(pre).includes('Φ ≤ >'));assert.ok(text(pre).includes('<script>remain text</script>'));assert.equal(pre.props.innerHTML,undefined)
    assert.ok(text(h.root).includes('Accuracy and completeness are unconfirmed'))
    const input=find(h.root,n=>n.tag==='input'&&n.props['aria-label']==='Physical page');input.props['onUpdate:modelValue'](3);await settle();await h.button('Go to page').props.onClick();await settle()
    assert.equal(h.calls[1].number,3);assert.equal(h.openModals().length,1,'The source-set modal closes before parsed text opens')
    h.button('Open original at page').props.onClick();await settle();assert.equal(h.openModals().length,1);assert.equal(h.pdfMounts[1].initialPage,3)
    assert.equal(h.findVisible(n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')),undefined,'Parsed text is hidden while its PDF is open')
    await h.closeModal();assert.equal(h.openModals().length,1);assert.ok(h.findVisible(n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')))
    assert.equal(h.calls.length,2,'Closing the PDF restores the same saved page without another request')
    assert.equal(h.tableCalls.length,2,'Closing the PDF does not fetch tables again')
    assert.equal(find(h.root,n=>n.props?.['data-test-pdf-preview']),undefined)
  }finally{h.dispose()}
})
test('Vetting source preparation refreshes reader and original pins once before later review stages',async()=>{
  const initial=source({fileName:'PRE.docx',pageCount:0,parsed:true})
  const active={id:'run-2',status:'RUNNING',phase:'queued',completedUnits:0,totalUnits:2}
  let progressReads=0
  const h=await mount({file:initial,cloneFiles:true,controlledTimers:true,startRun:()=>active,
    progress:()=>({...active,phase:++progressReads===1?'preparing':'rules'}),
    read:(file,number,pins)=>page({...file,...pins},number,{physicalPage:null,pageCount:0,textPart:number,textPartCount:2,
      pageNumberAuthority:'unpaginated_saved_text',text:'CURRENT PRE SAVED TEXT'})})
  try{
    await h.button('Run vetting').props.onClick();await settle()
    h.state.files[0]=source({...initial,businessContentSha256:'f'.repeat(64)})
    const nextPoll=async()=>{const timer=h.timers.find(item=>item.ms===2000&&!item.cancelled&&!item.used);assert.ok(timer);timer.used=true;timer.fn();await settle()}
    await nextPoll();assert.equal(progressReads,1)
    await nextPoll();assert.equal(progressReads,2)
    const refreshedCount=h.getFileReads();await nextPoll();assert.equal(h.getFileReads(),refreshedCount)
    const download=h.findVisible(n=>n.tag==='a'&&text(n).trim()==='Download original')
    assert.equal(new URL(download.props.href,'http://localhost').searchParams.get('businessContentSha256'),'f'.repeat(64))
    await h.open()
    assert.equal(h.calls[0].pins.businessContentSha256,'f'.repeat(64))
    assert.ok(text(h.root).includes('CURRENT PRE SAVED TEXT'))
  }finally{h.dispose()}
})
test('starting a review refreshes changed saved-text pins while the run is still preparing',async()=>{
  const initial=source({fileName:'PRE.docx',pageCount:0,parsed:true})
  const active={id:'run-preparing',status:'RUNNING',phase:'preparing',completedUnits:0,totalUnits:2}
  let h
  h=await mount({file:initial,cloneFiles:true,controlledTimers:true,
    startRun:()=>{h.state.files[0]=source({...initial,businessContentSha256:'f'.repeat(64)});return active},
    read:(file,number,pins)=>page({...file,...pins},number,{physicalPage:null,pageCount:0,textPart:number,textPartCount:1,
      pageNumberAuthority:'unpaginated_saved_text',text:'CURRENT PRE SAVED TEXT'})})
  try{
    const before=h.getFileReads()
    await h.button('Run vetting').props.onClick();await settle()
    assert.equal(h.getFileReads(),before+1,'Start completion refreshes the source list before phase changes')
    await h.open()
    assert.equal(h.calls[0].pins.businessContentSha256,'f'.repeat(64))
    assert.ok(text(h.root).includes('CURRENT PRE SAVED TEXT'))
  }finally{h.dispose()}
})
test('opening the source set during active preparation fetches current reader pins before showing its actions',async()=>{
  const initial=source({fileName:'PRE.docx',pageCount:0,parsed:true})
  const active={id:'run-3',status:'RUNNING',phase:'preparing',completedUnits:0,totalUnits:2}
  const h=await mount({file:initial,cloneFiles:true,controlledTimers:true,startRun:()=>active,
    read:(file,number,pins)=>page({...file,...pins},number,{physicalPage:null,pageCount:0,textPart:number,textPartCount:1,
      pageNumberAuthority:'unpaginated_saved_text',text:'CURRENT PRE SAVED TEXT'})})
  try{
    await h.closeModal()
    await h.button('Run vetting').props.onClick();await settle()
    h.state.files[0]=source({...initial,businessContentSha256:'f'.repeat(64)})
    await h.button('Review source set').props.onClick();await settle()
    assert.equal(h.openModals().length,1)
    await h.open()
    assert.equal(h.calls[0].pins.businessContentSha256,'f'.repeat(64))
    assert.ok(text(h.root).includes('CURRENT PRE SAVED TEXT'))
  }finally{h.dispose()}
})
test('pending and absent saved-page results are clear and do not claim a blank original',async()=>{
  for(const [file,status,expected] of [[source({parseStatus:'IDENTITY_PENDING'}),'detailed_parsing_not_started','Detailed parsing has not started'],[source(),'no_saved_text','does not confirm that the original page is blank']]){
    const h=await mount({file,read:(f,n)=>page(f,n,{status,text:''})});try{await h.open();assert.ok(text(h.root).includes(expected));assert.equal(find(h.root,n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')),undefined)}finally{h.dispose()}
  }
})

test('unpaginated DOCX exposes ordered text parts through visible controls without invented physical pages',async()=>{
  const file=source({fileName:'full-pre.docx',pageCount:0})
  const h=await mount({file,read:(f,n)=>page(f,n,{physicalPage:null,pageCount:0,textPart:n,textPartCount:3,
    pageNumberAuthority:'unpaginated_saved_text',text:`PART ${n}`})});try{
    await h.open();assert.equal(h.tableCalls.length,0);assert.ok(text(h.root).includes('Text part 1 / 3'))
    assert.ok(text(h.root).includes('original physical pages are unknown'))
    assert.equal(h.findVisible(n=>n.tag==='input'&&n.props['aria-label']==='Text part').props.disabled,undefined)
    assert.equal(h.findVisible(n=>Object.hasOwn(n.props??{},'data-parsed-tables')),undefined)
    assert.equal(h.button('Previous part').props.disabled,true);assert.equal(h.button('Next part').props.disabled,false)
    h.button('Next part').props.onClick();await settle();assert.equal(h.calls[1].number,2);assert.ok(text(h.root).includes('Text part 2 / 3'))
    const input=h.findVisible(n=>n.tag==='input'&&n.props['aria-label']==='Text part');input.props['onUpdate:modelValue'](3);await settle()
    h.button('Go to part').props.onClick();await settle();assert.equal(h.calls[2].number,3);assert.equal(h.button('Next part').props.disabled,true)
    h.button('Previous part').props.onClick();await settle();assert.equal(h.calls[3].number,2);assert.equal(h.tableCalls.length,0)
  }finally{h.dispose()}
  const falsePage=await mount({file,read:(f,n)=>page(f,n,{physicalPage:n,pageCount:0,textPart:n,textPartCount:2,pageNumberAuthority:'unpaginated_saved_text'})});try{
    await falsePage.open();assert.ok(text(falsePage.root).includes('The saved text is unavailable'))
    assert.equal(falsePage.findVisible(n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')),undefined)
  }finally{falsePage.dispose()}
})
test('missing stamps and mismatched response pins never display purported saved text',async()=>{
  const missing=await mount({file:source({businessContentSha256:null})});try{assert.equal(missing.button('View parsed text').props.disabled,true);await missing.open();assert.equal(missing.calls.length,0);assert.ok(text(missing.root).includes('Saved text unavailable; use the original.'))}finally{missing.dispose()}
  const changed=await mount({read:(f,n)=>page(f,n,{originalSourceSha256:'f'.repeat(64)})});try{await changed.open();assert.ok(text(changed.root).includes('source changed'));assert.equal(find(changed.root,n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')),undefined)}finally{changed.dispose()}
})
test('late page responses cannot cross a project ABA or reopen an old reader',async()=>{
  const pending=deferred(),h=await mount({read:()=>pending.promise});try{
    await h.open();h.store.activeProjectId='project-b';await settle();h.store.activeProjectId='project-a';await settle();pending.resolve(page(source(),1));await settle()
    assert.equal(find(h.root,n=>n.tag==='section'&&Object.hasOwn(n.props,'data-parsed-source-reader')),undefined)
  }finally{h.dispose()}
})

test('an open original unmounts on project ABA, a changed revision or reopening the source set',async()=>{
  for(const change of ['project','revision','sourceSet']){
    const h=await mount();try{
      await h.open();h.button('Open original at page').props.onClick();await settle();assert.equal(h.pdfMounts.length,1)
      if(change==='project'){h.store.activeProjectId='project-b';await settle();h.store.activeProjectId='project-a';await settle()}
      else if(change==='revision'){h.state.files[0]=source({businessContentSha256:'f'.repeat(64)});await settle()}
      else{h.button('Review source set').props.onClick();await settle();assert.equal(h.openModals().length,1)}
      assert.equal(h.pdfUnmounts.length,1);assert.equal(find(h.root,n=>n.props?.['data-test-pdf-preview']),undefined)
      assert.equal(h.findVisible(n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')),undefined,'A changed or replaced source view must not reveal old saved text')
    }finally{h.dispose()}
  }
})

test('missing original pins and excluded sources cannot mount an original PDF preview',async()=>{
  for(const file of [source({businessContentSha256:null,sourceHash:null,routingSha256:null}),source({documentScope:'drawing',exclusionReason:'drawing_outside_scope'})]){
    const h=await mount({file});try{assert.equal(h.button('Open original').props.disabled,true);h.button('Open original').props.onClick();await settle();assert.equal(h.pdfMounts.length,0)}finally{h.dispose()}
  }
})

test('large unstamped historical source exposes only its registered original',async()=>{
  const file=source({sourceRole:'tender',role:{en:'Tender provisions'},exclusionReason:null,sourceHash:null,businessContentSha256:null})
  const h=await mount({file});try{
    const download=h.findVisible(n=>n.tag==='a'&&Object.hasOwn(n.props,'data-source-original-download'))
    assert.ok(download)
    const original=new URL(download.props.href,'http://localhost')
    assert.equal(original.pathname,'/api/vetting/project-a/sources/85/registered-original')
    assert.deepEqual(Object.fromEntries(original.searchParams),{originalSourceSha256:file.originalSourceSha256,routingSha256:file.routingSha256})
    assert.equal(h.button('View parsed text').props.disabled,true)
    assert.ok(text(h.root).includes('Saved text unavailable; use the original.'))
    assert.equal(h.button('Open original').props.disabled,false)
    h.button('Open original').props.onClick();await settle()
    assert.equal(h.pdfMounts.length,1)
    assert.equal(new URL(h.pdfMounts[0].source,'http://localhost').pathname,original.pathname)
    assert.equal(h.calls.length,0);assert.equal(h.tableCalls.length,0)
  }finally{h.dispose()}
})

test('positively excluded drawings retain only the pinned original download without opening a reader',async()=>{
  for(const [fileName,documentScope,exclusionReason] of [['retained-drawing.pdf','drawing','drawing_outside_scope'],['retained-native.dwg',null,'source_not_eligible_for_vetting']]){
    const file=source({key:'DRAWING',fileName,documentScope,exclusionReason,status:'EXCLUDED',parseStatus:'EXCLUDED',pageCount:0})
    const h=await mount({file});try{
      const download=h.findVisible(n=>n.tag==='a'&&Object.hasOwn(n.props,'data-source-original-download'));assert.ok(download)
      const url=new URL(download.props.href,'http://localhost');assert.equal(url.pathname,'/api/vetting/project-a/sources/85/original')
      assert.deepEqual(Object.fromEntries(url.searchParams),{sourceHash:file.sourceHash,originalSourceSha256:file.originalSourceSha256,sourceRole:'unknown'})
      assert.equal(download.props.download,fileName);assert.equal(download.props.target,undefined)
      assert.equal(h.button('View parsed text').props.disabled,true);await h.open()
      if(fileName.endsWith('.pdf')){assert.equal(h.button('Open original').props.disabled,true);h.button('Open original').props.onClick();await settle()}
      assert.equal(h.calls.length,0);assert.equal(h.tableCalls.length,0);assert.equal(h.pdfMounts.length,0)
    }finally{h.dispose()}
  }
})

test('retained originals remain directly downloadable while parsing is unavailable or incomplete',async()=>{
  for(const status of ['FAILED','PARSE_FAILED','PARSING','PENDING','EXCLUDED']){
    const file=source({status,parseStatus:status,exclusionReason:null,documentScope:'package'})
    const h=await mount({file});try{
      const download=h.findVisible(n=>n.tag==='a'&&Object.hasOwn(n.props,'data-source-original-download'))
      assert.ok(download,`Original download must remain visible for ${status}`)
      for(let parent=download.parent;parent;parent=parent.parent)assert.notEqual(parent.tag,'details')
      const url=new URL(download.props.href,'http://localhost')
      if(['FAILED','PARSING'].includes(status)){
        assert.equal(url.pathname,'/api/vetting/project-a/sources/85/reader-original')
        assert.deepEqual(Object.fromEntries(url.searchParams),{originalSourceSha256:file.originalSourceSha256,routingSha256:file.routingSha256,businessContentSha256:file.businessContentSha256})
      }else{
        assert.equal(url.pathname,'/api/vetting/project-a/sources/85/original')
        assert.deepEqual(Object.fromEntries(url.searchParams),{sourceHash:file.sourceHash,originalSourceSha256:file.originalSourceSha256,sourceRole:file.sourceRole})
      }
      assert.equal(download.props.download,file.fileName)
      assert.equal(h.button('Open original').props.disabled,status!=='FAILED')
      assert.equal(h.button('View parsed text').props.disabled,true)
      assert.equal(h.calls.length,0);assert.equal(h.tableCalls.length,0)
    }finally{h.dispose()}
  }
})
test('large failed source keeps its readable original download without a legacy source hash',async()=>{
  const h=await mount({file:source({status:'FAILED',parseStatus:'FAILED',sourceHash:null,documentScope:'package',exclusionReason:null})})
  try{
    const download=h.findVisible(n=>n.tag==='a'&&Object.hasOwn(n.props,'data-source-original-download'))
    assert.ok(download)
    const url=new URL(download.props.href,'http://localhost')
    assert.equal(url.pathname,'/api/vetting/project-a/sources/85/reader-original')
    assert.equal(url.searchParams.get('businessContentSha256'),'c'.repeat(64))
    assert.equal(h.button('View parsed text').props.disabled,true)
  }finally{h.dispose()}
})

test('the drawing download fallback cannot bypass missing pins, unresolved identity or other exclusions',async()=>{
  const drawing=source({key:'DRAWING',fileName:'retained-drawing.pdf',documentScope:'drawing',exclusionReason:'drawing_outside_scope',status:'EXCLUDED',parseStatus:'EXCLUDED'})
  for(const extra of [{originalSourceSha256:null},{sourceHash:null},{sourceRole:'drawing'},{documentId:'bad'},{parseStatus:'PARTIAL'},
    {key:'BQ',documentScope:'pricing',exclusionReason:'pricing_document'},{exclusionReason:'evaluation_answer_isolated'}]){
    const h=await mount({file:{...drawing,...extra}});try{
      assert.equal(h.findVisible(n=>n.tag==='a'&&Object.hasOwn(n.props,'data-source-original-download')),undefined)
      assert.equal(h.pdfMounts.length,0);assert.equal(h.calls.length,0);assert.equal(h.tableCalls.length,0)
    }finally{h.dispose()}
  }
})

test('saved merged cells render once before native text, with literal content and unconfirmed relationships',async()=>{
  const h=await mount({readTables:(f,n)=>tablePage(f,n,{tableStatus:'saved_tables',tables:[grid(n)]})});try{
    await h.open();assert.deepEqual(h.tableCalls,h.calls)
    const table=h.findVisible(n=>Object.hasOwn(n.props??{},'data-saved-parser-table'));assert.ok(table)
    const cells=all(table,n=>n.tag==='td');assert.equal(cells.length,7);assert.equal(all(table,n=>n.tag==='tr').length,3)
    assert.equal(cells[0].props.rowspan,2);assert.equal(cells[0].props.colspan,1);assert.equal(text(cells[0]),'Test\nmethod')
    assert.equal(cells[1].props.colspan,2);assert.equal(text(cells[5]),'≤35 N');assert.equal(text(cells[6]),'≤20 N')
    assert.ok(text(table).includes('<script>literal</script>'));assert.equal(cells[4].props.innerHTML,undefined)
    assert.equal(all(table,n=>n.tag==='thead'||n.tag==='th').length,0)
    const reader=h.findVisible(n=>Object.hasOwn(n.props??{},'data-parsed-source-reader')),linear=all(reader,()=>true)
    assert.ok(linear.indexOf(table)<linear.indexOf(find(reader,n=>n.tag==='pre')))
    assert.ok(text(reader).includes('Saved parser table observation — unconfirmed'));assert.ok(text(reader).includes('Header and continuation relationships are unconfirmed'))
    assert.ok(text(reader).includes('Native cell grid'));assert.ok(!text(reader).includes('pdfplumber_lines_lines'));assert.ok(text(reader).includes('Text extraction can flatten columns'))
    const input=h.findVisible(n=>n.tag==='input'&&n.props['aria-label']==='Physical page');input.props['onUpdate:modelValue'](2);await settle();await h.button('Go to page').props.onClick();await settle()
    assert.equal(h.tableCalls[1].number,2);assert.ok(text(h.root).includes('Header and continuation relationships are unconfirmed'))
  }finally{h.dispose()}
})

test('saved geometry preserves fully spanned rows and orders anchors without inventing headers',async()=>{
  const h=await mount({readTables:(f,n)=>tablePage(f,n,{tableStatus:'saved_tables',tables:[
    {method:'unknown_internal_method',physicalPage:n,rowCount:2,colCount:1,continuationStatus:'unknown',cells:[{row:0,col:0,rowSpan:2,colSpan:1,text:'Two rows'}]},
    {...grid(n),cells:[...grid(n).cells].reverse()}
  ]})});try{
    await h.open();const tables=all(h.root,n=>Object.hasOwn(n.props??{},'data-saved-parser-table'))
    assert.equal(tables.length,2);assert.equal(all(tables[0],n=>n.tag==='tr').length,2);assert.equal(all(tables[0],n=>n.tag==='td').length,1)
    assert.equal(all(tables[1],n=>n.tag==='td').map(text).slice(0,2).join('|'),'Test\nmethod|Maximum force')
    assert.ok(text(tables[0]).includes('Saved cell grid'));assert.ok(!text(h.root).includes('unknown_internal_method'))
  }finally{h.dispose()}
})

test('empty capture statuses and legacy missing fields retain usable native text',async()=>{
  for(const status of ['no_saved_tables','unavailable','not_supported',undefined]){
    const h=await mount({readTables:(f,n)=>status===undefined?{documentId:f.documentId}:tablePage(f,n,{tableStatus:status})});try{
      await h.open();assert.ok(h.findVisible(n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')))
      assert.equal(h.findVisible(n=>Object.hasOwn(n.props??{},'data-saved-parser-table')),undefined)
      assert.ok(text(h.root).includes(status==='no_saved_tables'?'does not confirm that the original has no table':status===undefined?'Saved table observations are unavailable':'unavailable for this capture'))
    }finally{h.dispose()}
  }
})

test('invalid grids, identity and shared budgets suppress only saved tables',async()=>{
  const cases=[
    result=>{result.tables[0].cells.push({...result.tables[0].cells[0]})},
    result=>{result.tables[0].cells.pop()},
    result=>{result.tables[0].cells[0].rowSpan=4},
    result=>{result.tables[0].cells[0].row=-1},
    result=>{result.tables[0].cells[0].colSpan=0.5},
    result=>{result.tables[0].cells[0].text=null},
    result=>{result.tableStatus='inferred'},
    result=>{result.tableStatus='no_saved_tables'},
    result=>{result.tables=[]},
    result=>{result.physicalPage=2},
    result=>{result.tables[0].physicalPage=2},
    result=>{result.originalSourceSha256='f'.repeat(64)},
    result=>{result.tables[0].continuationStatus='continued'},
    result=>{result.semanticQuality='accepted'},
    result=>{delete result.canonicalAdoption},
    result=>{result.tables[0].rowCount=101},
    result=>{result.tables[0].colCount=31},
    result=>{result.tables[0].cells=Array.from({length:2001},()=>({...result.tables[0].cells[0]}))},
    result=>{result.tables=Array.from({length:11},()=>grid())},
    result=>{result.tables[0].cells[0].text='x'.repeat(200001)},
    result=>{result.tables=[{method:'a',physicalPage:1,rowCount:1,colCount:1,continuationStatus:'unknown',cells:[{row:0,col:0,rowSpan:1,colSpan:1,text:'x'.repeat(100001)}]},{method:'b',physicalPage:1,rowCount:1,colCount:1,continuationStatus:'unknown',cells:[{row:0,col:0,rowSpan:1,colSpan:1,text:'x'.repeat(100000)}]}]}
  ]
  for(const alter of cases){
    const h=await mount({readTables:(f,n)=>{const result=tablePage(f,n,{tableStatus:'saved_tables',tables:[grid(n)]});alter(result);return result}});try{
      await h.open();assert.equal(h.findVisible(n=>Object.hasOwn(n.props??{},'data-saved-parser-table')),undefined)
      assert.ok(text(h.root).includes('Saved table observations are unavailable'));assert.ok(h.findVisible(n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')))
    }finally{h.dispose()}
  }
})

test('text and table reads start together and can fail independently',async()=>{
  const pending=deferred(),h=await mount({read:()=>pending.promise,readTables:(f,n)=>tablePage(f,n,{tableStatus:'saved_tables',tables:[grid(n)]})});try{
    await h.open();assert.equal(h.calls.length,1);assert.equal(h.tableCalls.length,1);assert.ok(h.findVisible(n=>Object.hasOwn(n.props??{},'data-saved-parser-table')))
    pending.resolve(null);await settle();assert.ok(text(h.root).includes('The saved text is unavailable'));assert.ok(h.findVisible(n=>Object.hasOwn(n.props??{},'data-saved-parser-table')))
    h.findVisible(n=>n.tag==='input'&&n.props['aria-label']==='Physical page').props['onUpdate:modelValue'](3);await settle()
    h.button('Open original at page').props.onClick();await settle();assert.equal(h.pdfMounts[0].initialPage,1,'The original follows the visible table, even if the page input was edited')
  }finally{h.dispose()}
  const failed=await mount({readTables:()=>{throw Error('AUTHORED table failure')}});try{
    await failed.open();assert.ok(failed.findVisible(n=>n.tag==='pre'&&Object.hasOwn(n.props,'data-parsed-text')));assert.ok(text(failed.root).includes('Saved table observations are unavailable'))
  }finally{failed.dispose()}
  const absent=await mount({read:(f,n)=>page(f,n,{status:'no_saved_text',text:''}),readTables:(f,n)=>tablePage(f,n,{tableStatus:'saved_tables',tables:[grid(n)]})});try{
    await absent.open();assert.ok(absent.findVisible(n=>Object.hasOwn(n.props??{},'data-saved-parser-table')));assert.ok(text(absent.root).includes('No saved text is available'))
  }finally{absent.dispose()}
})

test('late saved tables cannot cross a page, project ABA, revision or closed reader',async()=>{
  for(const change of ['page','project','revision','close']){
    const pending=deferred(),h=await mount({readTables:(f,n)=>n===1?pending.promise:tablePage(f,n)});try{
      await h.open()
      if(change==='page'){h.findVisible(n=>n.tag==='input'&&n.props['aria-label']==='Physical page').props['onUpdate:modelValue'](2);await settle();await h.button('Go to page').props.onClick();await settle()}
      else if(change==='project'){h.store.activeProjectId='project-b';await settle();h.store.activeProjectId='project-a';await settle()}
      else if(change==='revision'){h.state.files[0]=source({businessContentSha256:'f'.repeat(64)});await settle()}
      else await h.closeModal()
      pending.resolve(tablePage(source(),1,{tableStatus:'saved_tables',tables:[grid()]}));await settle()
      assert.equal(h.findVisible(n=>Object.hasOwn(n.props??{},'data-saved-parser-table')),undefined)
      if(change==='page')assert.ok(text(h.root).includes('No saved table observations'))
      else assert.equal(h.findVisible(n=>Object.hasOwn(n.props??{},'data-parsed-source-reader')),undefined)
    }finally{h.dispose()}
  }
})
