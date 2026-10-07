import type { AppLocale } from './index'

const words = {
  'zh-Hans': {
    emptyRetrieval: '本次检索返回 0 条结果',
    openPage: '打开该页',
    noRetrievalRun: '该数据快照尚无已保存的检索运行；选择历史运行会切换到其所属快照。', revisions: '原图转录修订记录', oldOcrText: '修订前的 OCR 文本', revisedText: '当前修订文本', revisionBoundary: '保留原 OCR、原件与来源身份；修订未标为人工确认，也未提升整体 OCR 精度状态。置信度来自历史识别。',
    title: '数据检查', intro: '只读查看保存的实际数据。检查快照与上方项目工作区独立；不会重新解析、建索引或调用模型。', selectedText: '该阶段只表示召回返回的条目；不是语义上下文入选，也不代表已送模型审查。', rankingText: '排序与分数来自所选运行的真实保存记录；历史运行不代表当前索引已执行同一查询。', actualRequest: '实际送审请求（保存的消息 / 参数 / Schema）', ocrCoverageBoundary: '覆盖页数完整不等于 OCR 文本精度已确认；识别结果仍需逐页核对。', blocks: '保存文本块', otherExtraction: '文本层 / 提取来源未分层', wordPages: '该 Word 按段落和表格解析，尚未渲染为物理页；请到向量切片检查正文，或选择扫描 PDF 逐页核对。', reasonUnknown: '该状态的说明尚未记录；原始代码见保存的元信息。', vectorPoints: '数据库向量点（实际读取快照）', savedIndexText: '嵌入窗口来自保存的索引元信息；数据库 Point 单独展示实际只读导出快照，不把元信息当数据库内容。',
    chunks: '向量切片', ocr: '逐页 OCR', retrieval: '召回与送审', dataset: '数据快照', run: '审查运行', task: '任务 / 查询', stage: '阶段', search: '搜索原文', document: '文件', all: '全部', refresh: '刷新', loading: '正在读取真实记录…', unavailable: '没有可用的已保存记录', noData: '该范围没有记录', error: '读取失败', retry: '重试', prev: '上一页', next: '下一页', page: '页', total: '条记录', detail: '详细记录', select: '选择一条记录查看全文与来源', text: '原文全文', source: '来源与位置', metadata: '保存的元信息', native: '原生表格 / 单元格', windows: '嵌入窗口', original: '打开原件', ocrText: '保存的 OCR 文本', extraction: '提取文本', quality: '质量与覆盖', unknown: '未知 / 未记录', pageNo: '物理页', rank: '排序', before: '重排前', after: '重排后', score: '分数', reason: '处理 / 未送审原因', selected: '入选', sent: '最终送审', dense: 'Dense 召回', bm25: 'BM25 召回', rrf: 'RRF 合并', rerank: 'Rerank 重排', roles: '业务角色', inspect: '查看', hashes: '来源身份', readOnly: '只读', recorded: '已保存', unrecorded: '此阶段未保存记录；不推断排序、送审或 OCR 成功。', boundary: 'OCR 识别文本仍需核对；证据被运输或排序靠前不代表合同结论已确认。', sentText: '仅展示保存的实际送审材料，缺少记录不代表模型已审查。', snapshot: '快照来源', records: '记录', limit: '每页', query: '原始查询', status: '状态', chunkId: '切片 / Parent ID', viewText: '展开全文', close: '关闭', unavailableApi: '数据检查服务不可用，请查看服务状态；页面没有使用演示记录。'
  },
  'zh-Hant': {
    emptyRetrieval: '本次檢索傳回 0 筆結果',
    openPage: '開啟該頁',
    noRetrievalRun: '此資料快照尚無已保存的檢索執行；選擇歷史執行會切換至其所屬快照。', revisions: '原圖轉錄修訂記錄', oldOcrText: '修訂前的 OCR 文字', revisedText: '目前修訂文字', revisionBoundary: '保留原 OCR、原件與來源身分；修訂未標為人工確認，也未提升整體 OCR 精度狀態。信心值來自歷史識別。',
    title: '資料檢查', intro: '唯讀查看保存的實際資料。檢查快照與上方專案工作區獨立；不會重新解析、建索引或呼叫模型。', selectedText: '此階段只表示召回傳回的項目；不是語義上下文入選，也不代表已送模型審查。', rankingText: '排序與分數來自所選執行的真實保存記錄；歷史執行不代表目前索引已執行相同查詢。', actualRequest: '實際送審請求（保存的訊息 / 參數 / Schema）', ocrCoverageBoundary: '覆蓋頁數完整不等於 OCR 文字精度已確認；識別結果仍需逐頁核對。', blocks: '保存文字塊', otherExtraction: '文字層 / 提取來源未分層', wordPages: '此 Word 按段落和表格解析，尚未渲染為實體頁；請到向量切片檢查正文，或選擇掃描 PDF 逐頁核對。', reasonUnknown: '此狀態的說明尚未記錄；原始代碼見保存的中繼資料。', vectorPoints: '資料庫向量點（實際讀取快照）', savedIndexText: '嵌入視窗來自保存的索引中繼資料；資料庫 Point 單獨顯示實際唯讀匯出快照，不把中繼資料當資料庫內容。',
    chunks: '向量切片', ocr: '逐頁 OCR', retrieval: '召回與送審', dataset: '資料快照', run: '審查執行', task: '任務 / 查詢', stage: '階段', search: '搜尋原文', document: '檔案', all: '全部', refresh: '重新整理', loading: '正在讀取真實記錄…', unavailable: '沒有可用的已保存記錄', noData: '該範圍沒有記錄', error: '讀取失敗', retry: '重試', prev: '上一頁', next: '下一頁', page: '頁', total: '筆記錄', detail: '詳細記錄', select: '選擇一筆記錄查看全文與來源', text: '原文全文', source: '來源與位置', metadata: '保存的中繼資料', native: '原生表格 / 儲存格', windows: '嵌入視窗', original: '開啟原件', ocrText: '保存的 OCR 文字', extraction: '提取文字', quality: '品質與覆蓋', unknown: '未知 / 未記錄', pageNo: '實體頁', rank: '排序', before: '重排前', after: '重排後', score: '分數', reason: '處理 / 未送審原因', selected: '入選', sent: '最終送審', dense: 'Dense 召回', bm25: 'BM25 召回', rrf: 'RRF 合併', rerank: 'Rerank 重排', roles: '業務角色', inspect: '查看', hashes: '來源身分', readOnly: '唯讀', recorded: '已保存', unrecorded: '此階段未保存記錄；不推斷排序、送審或 OCR 成功。', boundary: 'OCR 識別文字仍需核對；證據被運輸或排序靠前不代表合約結論已確認。', sentText: '僅展示保存的實際送審材料，缺少記錄不代表模型已審查。', snapshot: '快照來源', records: '記錄', limit: '每頁', query: '原始查詢', status: '狀態', chunkId: '切片 / Parent ID', viewText: '展開全文', close: '關閉', unavailableApi: '資料檢查服務不可用，請查看服務狀態；頁面沒有使用示範記錄。'
  },
  en: {
    emptyRetrieval: 'This retrieval returned 0 results',
    openPage: 'Open page',
    noRetrievalRun: 'No saved retrieval run exists for this snapshot. Selecting a historical run switches to its own snapshot.', revisions: 'Transcription revisions from source images', oldOcrText: 'OCR text before revision', revisedText: 'Current revised text', revisionBoundary: 'Original OCR, source files and identities are retained. Revisions are not marked human-confirmed and do not promote overall OCR accuracy. Confidence belongs to historical recognition.',
    title: 'Data inspection', intro: 'Read saved actual data. Inspection snapshots are independent of the project workspace above. No parsing, index rebuild or model calls.', selectedText: 'This stage shows retrieval returned hits. It is not semantic context selection or evidence submitted for model review.', rankingText: 'Ranks and scores come from actual saved records for the selected run. A historical run does not mean the current index executed the same query.', actualRequest: 'Actual submitted request (saved messages / parameters / schema)', ocrCoverageBoundary: 'Complete page coverage does not confirm OCR text accuracy. The recognized text still needs page-by-page checking.', blocks: 'Saved text blocks', otherExtraction: 'Text layer / extraction source not separated', wordPages: 'This Word document was parsed as paragraphs and tables, without a rendered physical-page map. Inspect its body in Vector chunks or choose a scanned PDF for page-by-page checking.', reasonUnknown: 'A description for this status is not recorded. The raw code remains in saved metadata.', vectorPoints: 'Database vector points (actual read snapshot)', savedIndexText: 'Embedding windows come from saved index metadata. Database points are shown separately from an actual read-only export; metadata is not substituted for database contents.',
    chunks: 'Vector chunks', ocr: 'OCR by page', retrieval: 'Retrieval and submission', dataset: 'Data snapshot', run: 'Review run', task: 'Task / query', stage: 'Stage', search: 'Search source text', document: 'Document', all: 'All', refresh: 'Refresh', loading: 'Reading actual saved records…', unavailable: 'No saved records available', noData: 'No records in this scope', error: 'Read failed', retry: 'Retry', prev: 'Previous', next: 'Next', page: 'Page', total: 'records', detail: 'Record details', select: 'Select a record to inspect its full text and source', text: 'Full source text', source: 'Source and location', metadata: 'Saved metadata', native: 'Native tables / cells', windows: 'Embedding windows', original: 'Open original', ocrText: 'Saved OCR text', extraction: 'Extracted text', quality: 'Quality and coverage', unknown: 'Unknown / not recorded', pageNo: 'Physical page', rank: 'Rank', before: 'Before rerank', after: 'After rerank', score: 'Score', reason: 'Disposition / omission reason', selected: 'Selected', sent: 'Submitted evidence', dense: 'Dense retrieval', bm25: 'BM25 retrieval', rrf: 'RRF fusion', rerank: 'Rerank', roles: 'Source role', inspect: 'Inspect', hashes: 'Source identity', readOnly: 'Read only', recorded: 'Saved', unrecorded: 'This stage has no saved record. Ranking, submission and OCR success are not inferred.', boundary: 'OCR text still needs checking. Transported evidence or a high rank does not confirm a contractual conclusion.', sentText: 'Only saved submitted material is shown. Missing records do not mean the model reviewed the source.', snapshot: 'Snapshot origin', records: 'Records', limit: 'Page size', query: 'Original query', status: 'Status', chunkId: 'Chunk / parent ID', viewText: 'Full text', close: 'Close', unavailableApi: 'Inspection service is unavailable. Check its status; this page does not substitute demo records.'
  }
} as const

export function inspectionWords(locale: string) { return words[locale as AppLocale] ?? words['zh-Hans'] }

const reasons: Record<string, readonly [string, string, string]> = {
  available: ['可用', '可用', 'Available'],
  unavailable: ['不可用', '不可用', 'Unavailable'],
  completed: ['记录已完成', '記錄已完成', 'Recorded run completed'],
  complete: ['记录已完成（不代表质量确认）', '記錄已完成（不代表品質確認）', 'Record complete; quality is not confirmed'],
  partial: ['部分完成，仍需核对', '部分完成，仍需核對', 'Partial; checking is still required'],
  parsed: ['已解析（不代表文本质量已确认）', '已解析（不代表文字品質已確認）', 'Parsed; text quality is not confirmed'],
  needs_review: ['仍需人工核对', '仍需人工核對', 'Human checking required'],
  saved_persisted_metadata: ['已保存的索引元信息', '已保存的索引中繼資料', 'Saved index metadata'],
  recorded_actual_retrieval: ['实际召回记录已保存', '實際召回記錄已保存', 'Actual retrieval recorded'],
  recorded_extraction: ['提取文本已保存', '提取文字已保存', 'Extracted text recorded'],
  no_recorded_text: ['本页未保存提取文本', '此頁未保存提取文字', 'No extracted text recorded for this page'],
  physical_pages_unknown_or_not_recorded: ['没有记录物理页映射；请检查段落和表格切片', '沒有記錄實體頁映射；請檢查段落和表格切片', 'No physical-page map recorded; inspect paragraph and table chunks'],
  no_recorded_text_for_page: ['此物理页没有已保存的提取文本；请打开原件核对', '此實體頁沒有已保存的提取文字；請開啟原件核對', 'This physical page has no saved extracted text; check the original'],
  saved_parse_document_unavailable: ['未找到绑定的解析记录', '未找到綁定的解析記錄', 'Bound saved parse record unavailable'],
  this_retrieval_run_did_not_record_generation_dispatch_context: ['该召回运行没有保存模型送审上下文，不能据此声称已经送审', '此召回執行沒有保存模型送審上下文，不能據此聲稱已經送審', 'This retrieval run did not record model submission context; submission cannot be claimed'],
  independent_database_snapshot_not_recorded_for_this_dataset: ['该数据快照没有绑定独立读取的数据库向量点', '此資料快照沒有綁定獨立讀取的資料庫向量點', 'No independently read database point snapshot is bound to this dataset'],
  outside_final_retrieval_limit: ['超出本次召回返回条目上限', '超出本次召回傳回項目上限', 'Outside the final retrieval returned-hit limit'],
  not_in_reranker_input: ['未进入本次重排序输入', '未進入本次重新排序輸入', 'Not included in this reranker input'],
  not_selected_by_structural_unit_policy: ['未被选作本次返回的条款种子；可在附带完整条款中核对是否作为成员保留', '未被選作本次傳回的條款種子；可在附帶完整條款中核對是否作為成員保留', 'Not selected as a returned source-unit seed; inspect attached units to see whether it remains a member'],
  recorded_artifact_missing_or_identity_failed: ['保存的文件缺失或来源校验未通过', '保存的檔案缺失或來源校驗未通過', 'Saved artifact missing or source identity check failed'],
  saved_experiment_not_present_on_this_host: ['本机未保存该运行记录', '本機未保存此執行記錄', 'Saved experiment unavailable on this host'],
  page_image_not_recorded: ['未单独保存页图；可打开原 PDF', '未單獨保存頁圖；可開啟原 PDF', 'Separate page image not recorded; open the original PDF']
}

export function inspectionReason(locale: string, value: unknown): string {
  const w = inspectionWords(locale)
  if (value === null || value === undefined || value === '') return w.unknown
  const raw = String(value)
  if (raw === '—') return raw
  if (Object.values(w).some(x => x === raw)) return raw
  const found = reasons[raw.toLowerCase()]
  return found?.[locale === 'en' ? 2 : locale === 'zh-Hant' ? 1 : 0] ?? w.reasonUnknown
}

export function inspectionOcrReviewWords(locale: string) {
  const index = locale === 'en' ? 2 : locale === 'zh-Hant' ? 1 : 0
  const labels = {
    title: ['原图风险复核与识别对照', '原圖風險複核與識別對照', 'Source-image review and OCR controls'],
    original: ['保存的原件页图', '保存的原件頁圖', 'Saved original-page raster'],
    risks: ['该页已观察到的风险', '此頁已觀察到的風險', 'Observed risks on this page'],
    experiments: ['真实 OCR 配方对照', '實際 OCR 配方對照', 'Actual OCR configuration controls'],
    unapplied: ['未应用到当前来源', '未套用至目前來源', 'Not applied to the current source'],
    boundary: ['只读记录；仍需核对。本页复核不代表整份文件通过，识别置信度不代表文字准确率。', '唯讀記錄；仍需核對。此頁複核不代表整份文件通過，識別信心值不代表文字準確率。', 'Read-only observations; review still required. One reviewed page does not pass the document, and recognition confidence is not text accuracy.'],
    roi: ['仅覆盖该配方记录的识别范围；坐标已回映原整页，结果尚未应用。', '僅覆蓋此配方記錄的識別範圍；座標已回映原整頁，結果尚未套用。', 'Covers the recorded recognition region only. Coordinates map back to the original page; results remain unapplied.'],
    raw: ['实际识别原始全文', '實際識別原始全文', 'Actual raw recognition text'],
    boxes: ['原始行／置信度／两套坐标', '原始行／信心值／兩組座標', 'Raw lines, confidence and both coordinate systems'],
    parameters: ['配方与来源绑定', '配方與來源綁定', 'Configuration and source binding'],
    unavailable: ['该页没有可用的已绑定复核记录', '此頁沒有可用的已綁定複核記錄', 'No bound review observation is available for this page'],
    invalid: ['来源、数据快照或工件校验失败', '來源、資料快照或工件校驗失敗', 'Source, dataset or artifact validation failed'],
    notRegistered: ['复核工件尚未注册到该检查实例', '複核工件尚未註冊至此檢查實例', 'Review artifacts are not registered in this inspection instance'],
    notRecorded: ['该页尚未抽查；没有保存的复核观察', '此頁尚未抽查；沒有保存的複核觀察', 'This page has not been sampled; no review observation was recorded'],
    notRecordedDataset: ['该快照没有已注册的复核观察；不借用其他快照', '此快照沒有已註冊的複核觀察；不借用其他快照', 'No review observation is registered for this snapshot; observations from other snapshots are not borrowed'],
    noExperiments: ['该页没有已保存的真实识别配方对照', '此頁沒有已保存的實際識別配方對照', 'No actual OCR configuration control is recorded for this page']
  }
  return Object.fromEntries(Object.entries(labels).map(([key, values]) => [key, values[index]])) as Record<keyof typeof labels, string>
}

export function inspectionSourceUnitWords(locale: string) {
  const index = locale === 'en' ? 2 : locale === 'zh-Hant' ? 1 : 0
  const labels = {
    title: ['附带完整条款', '附帶完整條款', 'Attached complete source units'],
    order: ['展示顺序', '顯示順序', 'Display order'],
    members: ['同来源完整成员', '同來源完整成員', 'Complete members from the same source'],
    boundary: ['这些成员随命中种子返回，没有独立排序分数。此处可核对补齐的原文；实际预算和送审另查记录。', '這些成員隨命中種子傳回，沒有獨立排序分數。此處可核對補齊的原文；實際預算與送審另查記錄。', 'Members accompany a retrieved seed without independent ranking scores. Inspect the attached source text here; budget and submission require separate records.'],
    seed: ['命中种子的原始排名与分数', '命中種子的原始排名與分數', 'Original rank and score of the retrieved seed'],
    seedRank: ['种子原始排名', '種子原始排名', 'Original seed rank'],
    seedScore: ['种子分数', '種子分數', 'Seed score'],
    record: ['完整结构单元记录', '完整結構單元記錄', 'Full recorded source unit'],
    noMembers: ['该结构单元没有已确认的完整成员；保留原始未知状态。', '此結構單元沒有已確認的完整成員；保留原始未知狀態。', 'No complete members are confirmed for this unit; its recorded unknown status is retained.']
  }
  return Object.fromEntries(Object.entries(labels).map(([key, values]) => [key, values[index]])) as Record<keyof typeof labels, string>
}

