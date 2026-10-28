const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createReviewReducer } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/reducer.js'), { globals: { structuredClone }, dependencies: { './value-dependencies.js': './value-dependencies.js' } })
const node = id => ({ id, nameZh: id, nameEn: id, type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', docIds: [], sourceUnitIds: [], relatedInputIds: [], editTargets: [], evidence: [], targets: [], relations: [] })
const value = node('value'), owner = node('owner')
value.definitionOrigin = { kind: 'manual', reason: 'Human definition' }
value.catalogAlignment = { status: 'matched', confirmed: true, baselineKey: 'example', reason: 'Reviewed' }
owner.correction = { kind: 'rule', branches: [{ id: 'case', clauses: [{ inputId: 'value', operator: 'yes' }] }] }
owner.editTargets = [{ action: 'fill', valueSourceId: 'value' }]
const state = { variables: [value, owner], sourceReviews: {}, events: [], nextId: 1 }
const reduce = createReviewReducer({ sources: [] }).reduce
const confirmed = reduce(state, { type: 'CONFIRM_DEFINITION', id: 'value' })
assert.equal(confirmed.variables[0].definitionReview.confirmed, true)
assert.equal(confirmed.variables[0].definitionReview.basis, null)
assert.equal(confirmed.variables[0].status, 'pending')
const classified = reduce(confirmed, { type: 'EDIT', id: 'value', values: { role: { kind: 'verification' } } })
const result = classified.variables[1]
assert.equal(result.correction.needsRebind, true)
assert.equal(result.correction.supersededCorrection.branches[0].clauses[0].inputId, 'value')
assert.equal(result.editTargets[0].needsRebind, true)
assert.equal(result.editTargets[0].valueSourceId, undefined)
assert.equal(result.editTargets[0].supersededValueReference.valueSourceId, 'value')
assert.equal(classified.variables[0].definitionOrigin, undefined)
assert.equal(classified.variables[0].supersededDefinitionOrigin.reason, 'Human definition')
assert.equal(classified.variables[0].catalogAlignment.confirmed, false)
assert.equal(classified.variables[0].definitionReview, undefined)
assert.equal(reduce(classified, { type: 'CONFIRM_DEFINITION', id: 'value' }), classified)
const parent = node('parent'), child = { ...node('child'), parentId: 'parent' }
const parentState = { ...state, variables: [parent, child] }
assert.equal(reduce(parentState, { type: 'EDIT', id: 'parent', values: { role: { kind: 'verification' } } }), parentState)
const local = node('local'), root = node('root')
local.catalogAlignment = { status: 'unreviewed', confirmed: false, proposedMatches: [{ baselineKey: 'format', factProjection: { branchValue: 'electronic', branchPolarity: true } }] }
local.editTargets = [{ id: 'a', action: 'delete', text: 'Alternative A', docId: '41' }]
local.docIds = ['41']; root.docIds = ['41']; root.type = { kind: 'choice' }; root.options = [{ value: 'electronic' }, { value: 'paper' }]
root.catalogAlignment = { status: 'matched', confirmed: true, baselineKey: 'format' }
const projectionState = { ...state, variables: [local, root] }
const conversion = { type: 'CONVERT_FACT_PROJECTION', id: 'local', rootId: 'root', baselineKey: 'format', branchValue: 'electronic', branchPolarity: true, exhaustiveConfirmed: true }
assert.equal(reduce(projectionState, { ...conversion, exhaustiveConfirmed: false }), projectionState)
const projected = reduce(projectionState, conversion)
assert.equal(projected.variables[0].role.kind, 'derived')
assert.equal(projected.variables[0].correction.branches[1].resultValue, false)
assert.equal(projected.variables[0].editTargets[0].text, 'Alternative A')
assert.equal(projected.variables[0].editTargets[0].needsRebind, true)
const reverted = reduce(projected, { type: 'REVERT_FACT_PROJECTION', id: 'local' })
assert.equal(reverted.variables[0].role.kind, 'input')
assert.equal(reverted.variables[0].editTargets[0].text, 'Alternative A')
assert.equal(reverted.variables[0].editTargets[0].needsRebind, true)
const editionReduce = createReviewReducer({ sources: [{ id: '41', fileKey: 'SCC', format: 'docx' }, { id: '42', fileKey: 'SCC', format: 'docx' }] }).reduce
const otherEdition = { ...projectionState, variables: [local, { ...root, docIds: ['42'] }] }
assert.equal(editionReduce(otherEdition, conversion), otherEdition, 'Different Word editions remain distinct even with the same file format')
const unproposed = { ...projectionState, variables: [{ ...local, catalogAlignment: { status: 'unreviewed', confirmed: false, proposedMatches: [] } }, root] }
const manual = { ...conversion, origin: 'manual', reason: 'Checked the current source and option direction', branchValue: 'paper', branchPolarity: false }
assert.equal(reduce(unproposed, { ...manual, reason: '  ' }), unproposed, 'Manual links require a reason')
assert.equal(reduce(unproposed, { ...manual, exhaustiveConfirmed: false }), unproposed, 'Manual links require explicit exhaustive confirmation')
assert.equal(reduce(unproposed, { ...manual, branchValue: 'missing' }), unproposed, 'The selected branch must exist on the root')
const manualProjected = reduce(unproposed, manual), manualReview = manualProjected.variables[0].factProjectionReview
assert.equal(manualReview.origin, 'manual')
assert.equal(manualReview.reason, manual.reason)
assert.equal(manualProjected.variables[0].correction.branches[0].resultValue, false)
assert.equal(manualProjected.variables[0].editTargets[0].needsRebind, true)
assert.deepEqual(manualReview.original.catalogAlignment, unproposed.variables[0].catalogAlignment)
const conflict = { ...unproposed, variables: [{ ...unproposed.variables[0], catalogAlignment: { status: 'unreviewed', confirmed: false, proposedMatches: [{ baselineKey: 'format', semanticStatus: 'conflict', factProjection: { branchValue: 'paper', branchPolarity: false } }] } }, root] }
assert.equal(reduce(conflict, conversion), conflict, 'A conflicting model proposal cannot be automatically accepted')
assert.equal(reduce(conflict, manual).variables[0].factProjectionReview.origin, 'manual', 'A human can explicitly correct a conflicting suggestion')
assert.equal(editionReduce({ ...unproposed, variables: [unproposed.variables[0], { ...root, docIds: ['42'] }] }, manual).variables[0].role.kind, 'input', 'Manual linking does not bypass edition isolation')
const extra=node('extra'),oldRule={kind:'rule',branches:[{id:'old-branch',mode:'all',clauses:[{inputId:'local',operator:'yes'},{inputId:'extra',operator:'yes'}],resultValue:true}]}
const existingRuleState={...state,variables:[{...unproposed.variables[0],correction:oldRule,editTargets:[{id:'target-old',docId:'41',text:'Alternative A',action:'delete',branchId:'old-branch',needsRebind:false}]},root,extra]}
const projectedOld=reduce(existingRuleState,manual),oldReview=projectedOld.variables[0].factProjectionReview
assert.deepEqual(oldReview.original.correction,oldRule)
assert.equal(oldReview.original.targetBindings[0].branchId,'old-branch')
assert.equal(oldReview.previousRuleResolution.status,'pending')
assert.equal(projectedOld.variables[0].editTargets[0].branchId,undefined)
assert.equal(projectedOld.variables[0].editTargets[0].needsRebind,true)
assert.equal(reduce(projectedOld,{type:'RESOLVE_PREVIOUS_PROJECTION_RULE',id:'local',decision:'replace',reason:'replace',checked:true}),projectedOld,'A rule with another input cannot be silently replaced')
const rebuiltRule={kind:'rule',branches:[{id:'new-branch',mode:'all',clauses:[{inputId:'root',operator:'eq',value:'paper'},{inputId:'extra',operator:'yes'}],resultValue:false}]}
const rebuilt=reduce(projectedOld,{type:'RULE',id:'local',correction:rebuiltRule})
assert.deepEqual(rebuilt.variables[0].factProjectionReview.original.correction,oldRule,'Manual rebuilding retains the complete old rule')
const reviewedRebuild=reduce(rebuilt,{type:'RESOLVE_PREVIOUS_PROJECTION_RULE',id:'local',decision:'rebuilt',reason:'Compared all clauses and branches',checked:true})
assert.equal(reviewedRebuild.variables[0].factProjectionReview.previousRuleResolution.decision,'rebuilt')
const restoredRule=reduce(JSON.parse(JSON.stringify(reviewedRebuild)),{type:'REVERT_FACT_PROJECTION',id:'local'})
assert.deepEqual(restoredRule.variables[0].correction,oldRule);assert.equal(restoredRule.variables[0].editTargets[0].branchId,'old-branch');assert.equal(restoredRule.variables[0].editTargets[0].needsRebind,true)
const changedTargets=JSON.parse(JSON.stringify(reviewedRebuild));changedTargets.variables[0].editTargets.push({id:'new-target',docId:'41',text:'New range'})
assert.equal(reduce(changedTargets,{type:'REVERT_FACT_PROJECTION',id:'local'}),changedTargets,'Do not guess target identity after a target was added')
const applicableTarget = { ...node('conditional'), status:'confirmed', definitionReview:{confirmed:true}, catalogAlignment:{status:'matched',confirmed:true,baselineKey:'controlled',baselineVersion:'v1'} }
const prerequisite = { ...node('prerequisite'), status:'confirmed', catalogAlignment:{status:'matched',confirmed:true,baselineKey:'root',baselineVersion:'v1'} }
const appState = { ...state, variables:[applicableTarget,prerequisite] }
const applicability = {kind:'fixed_catalog',ruleVersion:'v1',targetBaselineKey:'controlled',bindingFingerprint:'source-basis-hash',mode:'all',confirmed:true,reason:'Checked this template',clauses:[{baselineKey:'root',inputId:'prerequisite',operator:'eq',value:false}]}
const appAction = {type:'SET_FIXED_APPLICABILITY',id:'conditional',applicability}
assert.equal(reduce(appState,{...appAction,applicability:{...applicability,reason:' '}}),appState)
assert.equal(reduce(appState,{...appAction,applicability:{...applicability,bindingFingerprint:''}}),appState)
const appApplied=reduce(appState,appAction)
assert.equal(appApplied.variables[0].applicability.reason,'Checked this template')
assert.equal(appApplied.variables[0].definitionReview,undefined)
assert.equal(appApplied.variables[0].status,'pending')
assert.equal(reduce(appApplied,appAction),appApplied,'An existing applicability is not silently overwritten')
const appRemoved=reduce(appApplied,{type:'CLEAR_FIXED_APPLICABILITY',id:'conditional'})
assert.equal(appRemoved.variables[0].applicability,undefined)
assert.equal(appRemoved.variables[0].status,'pending')
const cyclicState = {...appState,variables:[applicableTarget,{...prerequisite,role:{kind:'derived'},correction:{kind:'rule',branches:[{id:'case',mode:'all',clauses:[{inputId:'conditional',operator:'yes'}]}]}}]}
assert.equal(reduce(cyclicState,appAction),cyclicState,'Applicability cannot create a cycle with derived-value dependencies')
const pdfSource={id:'pdf-1',fileKey:'SCC',format:'pdf',sourceSha256:'sha-pdf'}
const pdfNode={...node('pdf-node'),docIds:['pdf-1'],semanticSourceQuote:'Current exact PDF quotation',sourceAnchors:[{docId:'pdf-1',sourceSha256:'sha-pdf',text:'Current exact PDF quotation',sourceObservationBinding:{status:'bound'}}]}
const pdfState={...state,variables:[pdfNode]}
const pdfReducer=createReviewReducer({sources:[pdfSource]}).reduce
const pdfAlignment={status:'confirmed',confirmed:true,sourceId:'pdf-1',sourceSha256:'sha-pdf',inputRef:'pdf-fact',sourceQuote:'exact PDF',reason:'Compared source meaning',requestId:'request-1',basis:null}
const pdfAction={type:'SET_PDF_REFERENCE_ALIGNMENT',id:'pdf-node',alignment:pdfAlignment}
assert.equal(pdfReducer(pdfState,{...pdfAction,alignment:{...pdfAlignment,sourceQuote:'not in source'}}),pdfState)
assert.equal(pdfReducer(pdfState,{...pdfAction,alignment:{...pdfAlignment,sourceSha256:'word-sha'}}),pdfState)
const pdfSet=pdfReducer(pdfState,pdfAction)
assert.equal(pdfSet.variables[0].pdfReferenceAlignment.inputRef,'pdf-fact')
assert.equal(pdfSet.variables[0].status,'pending')
const pdfCleared=pdfReducer(pdfSet,{type:'CLEAR_PDF_REFERENCE_ALIGNMENT',id:'pdf-node'})
assert.equal(pdfCleared.variables[0].pdfReferenceAlignment.confirmed,false)
assert.equal(pdfCleared.variables[0].pdfReferenceAlignment.sourceQuote,'exact PDF','Revoke retains the historical correspondence')
const pdfReplaced=pdfReducer(pdfCleared,{...pdfAction,alignment:{...pdfAlignment,inputRef:'other-fact',requestId:'request-2'}})
assert.equal(pdfReplaced.variables[0].pdfReferenceAlignmentHistory[0].inputRef,'pdf-fact')
assert.equal(pdfReplaced.variables[0].pdfReferenceAlignmentHistory[0].sourceQuote,undefined,'History does not duplicate original text')

const equalJson = (actual, expected) => assert.equal(JSON.stringify(actual), JSON.stringify(expected))
const originalDecision = { ...node('decision'), role: { kind: 'input', zh: '项目事实', en: 'Input' }, definitionOrigin: { kind: 'manual', reason: 'Original' }, catalogAlignment: { status: 'matched', confirmed: true, baselineKey: 'choice', baselineVersion: 'v1' } }
const dependent = { ...node('dependent'), correction: { kind: 'rule', branches: [{ id: 'only', mode: 'all', clauses: [{ inputId: 'decision', operator: 'yes' }] }] }, editTargets: [{ id: 'target-1', action: 'fill', valueSourceId: 'decision', text: 'Source wording' }], relatedInputIds: ['decision'] }
const reversibleState = { ...state, variables: [originalDecision, dependent] }
const mark = reduce(reversibleState, { type: 'EDIT', id: 'decision', at: 'mark', values: { role: { kind: 'verification' }, editorialReview: { kind: 'editorial', confirmed: true } } })
assert.equal(mark.variables[0].editorialClassificationHistory[0].original.role.kind, 'input')
assert.equal(mark.variables[0].editorialClassificationHistory[0].original.catalogAlignment.confirmed, true)
equalJson(mark.variables[0].editorialClassificationHistory[0].affectedOwners, ['dependent'])
equalJson(mark.variables[1].editorialReferenceHistory[0].correction, dependent.correction)
equalJson(mark.variables[1].editorialReferenceHistory[0].targets[0].original, dependent.editTargets[0])
assert.equal(mark.variables[1].correction.needsRebind, true)
assert.equal(mark.variables[1].editTargets[0].valueSourceId, undefined)
const roundTrip = JSON.parse(JSON.stringify(mark))
const restored = reduce(roundTrip, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'decision', at: 'restore' })
assert.equal(restored.variables[0].role.kind, 'input')
assert.equal(restored.variables[0].definitionOrigin.reason, 'Original')
assert.equal(restored.variables[0].catalogAlignment.baselineKey, 'choice')
assert.equal(restored.variables[0].catalogAlignment.confirmed, false)
assert.equal(restored.variables[0].editorialClassificationHistory[0].restored, true)
assert.equal(restored.variables[0].editorialClassificationHistory[0].referencesRequireReview, true)
equalJson(restored.variables[1].correction, mark.variables[1].correction)
equalJson(restored.variables[1].editTargets, mark.variables[1].editTargets)
assert.equal(restored.variables[1].editorialReferenceHistory[0].correction.branches[0].clauses[0].inputId, 'decision')
assert.equal(reduce(restored, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'decision' }), restored)
const reclassified = reduce(restored, { type: 'EDIT', id: 'decision', values: { role: { kind: 'verification' }, editorialReview: { kind: 'editorial', confirmed: true } } })
assert.equal(reclassified.variables[0].editorialClassificationHistory.length, 2)
const reclassifiedAgain = reduce(reclassified, { type: 'EDIT', id: 'decision', values: { role: { kind: 'verification' } } })
assert.equal(reclassifiedAgain.variables[0].editorialClassificationHistory.length, 2)
const editedValue = reduce(mark, { type: 'EDIT', id: 'decision', values: { nameEn: 'Human amended question', definitionOrigin: { kind: 'manual', reason: 'New human definition' }, catalogAlignment: { status: 'new', confirmed: true, reason: 'New human choice' } } })
const newRule = { kind: 'rule', needsRebind: true, branches: [], inputIds: [], humanNote: 'Edited later' }
const editedOwner = reduce(editedValue, { type: 'RULE', id: 'dependent', correction: newRule })
const manualTargets = [{ id: 'target-1', action: 'check', text: 'Human revised target', needsRebind: true }]
const editedOwnerTarget = reduce(editedOwner, { type: 'TARGETS', id: 'dependent', targets: manualTargets })
const restoredAfterEdit = reduce(editedOwnerTarget, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'decision' })
assert.equal(restoredAfterEdit.variables[0].nameEn, 'Human amended question')
assert.equal(restoredAfterEdit.variables[0].definitionOrigin.reason, 'New human definition')
assert.equal(restoredAfterEdit.variables[0].catalogAlignment.reason, 'New human choice')
equalJson(restoredAfterEdit.variables[1].correction, newRule)
equalJson(restoredAfterEdit.variables[1].editTargets, manualTargets)
equalJson(restoredAfterEdit.variables[1].editorialReferenceHistory[0].correction, dependent.correction)
const changedMeaning = reduce(mark, { type: 'EDIT', id: 'decision', values: { questionEn: 'Different project question' } })
const restoredChanged = reduce(changedMeaning, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'decision' })
assert.equal(restoredChanged.variables[0].definitionOrigin, undefined)
assert.equal(restoredChanged.variables[0].catalogAlignment.confirmed, false)
assert.equal(restoredChanged.variables[0].editorialClassificationHistory[0].original.definitionOrigin.reason, 'Original')

const realDecision = { ...node('real-decision'), role: { kind: 'decision', zh: '决定', en: 'Decision' } }
const decisionState = { ...state, variables: [realDecision] }
const markedDecision = reduce(decisionState, { type: 'EDIT', id: 'real-decision', values: { role: { kind: 'verification' } } })
assert.equal(reduce(markedDecision, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'real-decision' }).variables[0].role.kind, 'decision')
const humanRoleEdit = reduce(mark, { type: 'EDIT', id: 'decision', values: { role: { kind: 'verification', zh: '人工作用说明', en: 'Changed check' } } })
assert.equal(reduce(humanRoleEdit, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'decision' }), humanRoleEdit, 'A later role edit is never silently overwritten')
const projectedClassified = reduce(projected, { type: 'EDIT', id: 'local', values: { role: { kind: 'verification' }, editorialReview: { kind: 'editorial', confirmed: true } } })
assert.equal(projectedClassified.variables[0].role.kind, 'verification')
assert.equal(projectedClassified.variables[0].factProjectionReview, undefined)
assert.equal(projectedClassified.variables[0].editorialClassificationHistory[0].original.factProjectionReview.rootId, 'root')
assert.equal(projectedClassified.variables[0].correction.needsRebind, true)
const projectedRestored = reduce(projectedClassified, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'local' })
assert.equal(projectedRestored.variables[0].role.kind, 'derived')
assert.equal(projectedRestored.variables[0].factProjectionReview, undefined)
assert.equal(projectedRestored.variables[0].correction.needsRebind, true)
assert.equal(projectedRestored.variables[0].editorialClassificationHistory[0].original.factProjectionReview.rootId, 'root')
const changedAtClassification = reduce(reversibleState, { type: 'EDIT', id: 'decision', values: { role: { kind: 'verification' }, nameEn: 'Reworded on classification' } })
assert.equal(changedAtClassification.variables[0].editorialClassificationHistory[0].original.catalogAlignment.confirmed, true)
const restoredAfterRewording = reduce(changedAtClassification, { type: 'RESTORE_BUSINESS_CLASSIFICATION', id: 'decision' })
assert.equal(restoredAfterRewording.variables[0].nameEn, 'Reworded on classification')
assert.equal(restoredAfterRewording.variables[0].definitionOrigin, undefined)
assert.equal(restoredAfterRewording.variables[0].catalogAlignment.confirmed, false)
