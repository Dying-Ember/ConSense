// Real parent + candidate controls with a transport fixture enforcing the existing candidate revision guard.
const assert = require('node:assert/strict')
const { harness } = require('./drafting-saved-review-navigation.test.cjs')
const { find, text, settle } = require('./helpers/render-controls.cjs')

;(async () => {
  const h = harness({ strictCandidateRevision: true })
  try {
    await settle(); await h.saveReview(); await h.title('3 Adopt and plan')
    assert.ok(Boolean(h.button('Correct candidate or assignment')), 'Assigned candidates need the normal correction entry in step 3, including old-run candidates')
    assert.ok(Boolean(h.control('data-candidate-impact')), 'Correction shows the matching working revision and amendment context')
    await h.title('Adopt this candidate')
    assert.match(text(h.root), /GRAPH_REVISION_CONFLICT/)
    assert.equal(h.adoptions, 0, 'The existing server revision guard remains effective')
    assert.equal(h.writes.length, 2, 'A rejected adoption does not automatically correct or retry the candidate')
    await h.title('Correct candidate or assignment')
    const submit = h.button('Save correction without adoption')
    assert.equal(submit.props.disabled, false, 'A user can explicitly verify the same value, quote and target without inventing an edit')
    const form = find(h.root, node => node.tag === 'form')
    const barrier = h.pauseCorrection(), saving = form.props.onSubmit({ preventDefault() {} }); await settle()
    assert.equal(h.button('2 Variables and graph').props.disabled, true, 'Unchanged verification in flight still protects candidate controls and navigation')
    await h.title('2 Variables and graph')
    assert.equal(h.button('3 Adopt and plan').props['aria-current'], 'step')
    barrier.resolve(); await saving; await settle()
    const correction = h.writes.find(write => write?.kind === 'correctCandidate')
    assert.deepEqual(correction, { kind: 'correctCandidate', targetRevision: 'TEST-draft-2', key: 'TEST-question', patch: { expectedRunId: 'TEST-original-run', candidateIndex: 0, value: 'TEST candidate', sourceQuote: 'TEST ONLY exact evidence', targetKey: 'TEST-question' } })
    assert.equal(h.adoptions, 0, 'Saving candidate verification does not adopt a fact')
    await h.title('Adopt this candidate')
    assert.equal(h.adoptions, 1, 'A separate explicit adoption uses the corrected current revision')
    assert.equal(h.writes.at(-1).candidateSnapshot.extractionRunId, 'TEST-corrected-run')
    assert.deepEqual(h.modelCalls, [])
    await h.click('data-candidate-question')
    assert.equal(h.button('2 Variables and graph').props['aria-current'], 'step', 'The candidate can open its real template variable for source review')
    console.log('PASS: assigned old-run candidates can be explicitly verified then separately adopted; navigation preserves the server revision guard')
  } finally { h.dispose() }

  const dirty = harness()
  try {
    await settle(); await dirty.saveReview(); await dirty.title('3 Adopt and plan'); await dirty.title('Correct candidate or assignment')
    dirty.control('data-candidate-quote').props['onUpdate:modelValue']('TEST unfinished quotation'); await settle()
    const before = dirty.reads
    await dirty.title('2 Variables and graph')
    assert.equal(dirty.reads, before)
    assert.equal(dirty.button('3 Adopt and plan').props['aria-current'], 'step')
    assert.equal(dirty.control('data-candidate-quote').value, 'TEST unfinished quotation')
    assert.deepEqual(dirty.writes, ['reviewSave'])
    console.log('PASS: opening assigned-candidate correction retains ordinary dirty-navigation protection')
  } finally { dirty.dispose() }

  const stale = harness({ staleSources: true })
  try {
    await settle(); await stale.saveReview(); await stale.title('3 Adopt and plan'); await stale.title('Correct candidate or assignment')
    assert.match(text(stale.root), /Correspondence sources changed/)
    assert.equal(stale.button('Save correction without adoption').props.disabled, true, 'An explicit unchanged verification cannot bypass stale-source blocking')
    assert.deepEqual(stale.writes, ['reviewSave'])
    console.log('PASS: stale source evidence still blocks correction')
  } finally { stale.dispose() }
})().catch(error => { console.error(error); process.exitCode = 1 })
