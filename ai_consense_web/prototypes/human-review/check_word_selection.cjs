'use strict';

// Offline regression for the preview's actual geometry and sparse-anchor helpers.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const args = process.argv.slice(2);
assert.ok(args.length === 0 || (args.length === 2 && args[0] === '--source-view'),
  'Usage: node check_word_selection.cjs [--source-view ABSOLUTE_PATH]');
if (args.length) assert.ok(path.isAbsolute(args[1]), 'Source view must be an absolute path.');

const script = fs.readFileSync(path.join(__dirname, 'prototype.logic.js'), 'utf8');
const cut = script.indexOf("  const source = JSON.parse($('source-data').textContent);");
assert.ok(cut > 0, 'Pure helper boundary must remain identifiable.');
const context = vm.createContext({structuredClone,
  document: {getElementById: id => {
    assert.equal(id, 'prototype-data');
    return {textContent: JSON.stringify({variables: [], sourceUnits: []})};
  }}
});
vm.runInContext(script.slice(0, cut) + `
  globalThis.selectionHelpers = {containsWord, visualWordIds, wordsInBox, selectedText};
})();`, context);
const api = context.selectionHelpers;
const plain = value => JSON.parse(JSON.stringify(value));
const word = (text, x, y, w = 24, h = 12) => ({text, x, y, w, h});
const words = [word('Main1', 10, 10), word('Side1', 100, 10),
  word('Main2', 10, 24), word('Main3', 10, 38),
  word('Side2', 100, 24), word('Side3', 100, 38), word('–', 130, 10.8, 5)];
const a = {x: 95, y: 5}, b = {x: 140, y: 52};
const ids = plain(api.wordsInBox(words, a, b));
assert.deepEqual(ids, [1, 6, 4, 5], 'Box selection follows visible rows, not interleaved content order.');
assert.deepEqual(plain(api.wordsInBox(words, b, a)), ids, 'Reverse dragging selects the same words.');
assert.equal(api.selectedText(words, ids), 'Side1 –\nSide2\nSide3');
assert.deepEqual(plain(api.wordsInBox(words, {x: 95, y: 20}, {x: 140, y: 20.1})), [1, 6],
  'A thin horizontal drag within a text line still selects its whole words.');
assert.deepEqual(plain(api.wordsInBox(words, {x: 140, y: 20.1}, {x: 95, y: 20})), [1, 6]);
assert.deepEqual(plain(api.wordsInBox(words, {x: 200, y: 200}, {x: 220, y: 220})), []);
assert.deepEqual(plain(api.visualWordIds(words, [5, 1, 4, 1, 6])), ids, 'Duplicate picks are removed.');

const sparse = {start: 1, end: 7, wordIds: ids.filter(i => i !== 4)};
assert.equal(api.containsWord(sparse, 1), true);
for (const i of [0, 2, 3, 4]) assert.equal(api.containsWord(sparse, i), false,
  'Removing a word or leaving an interior content-order gap must not refill it.');
assert.equal(api.containsWord({start: 1, end: 7, wordIds: []}, 2), false,
  'An empty explicit selection must not fall back to a continuous range.');
assert.equal(api.containsWord({start: 1, end: 3}, 2), true, 'Historical continuous anchors remain supported.');
assert.equal(api.containsWord({start: 1, end: 3}, 3), false);

// Exercise the actual renderer and click lookup, so interval-based consumers cannot silently return.
const renderBody = script.slice(script.indexOf('  function renderPage(){'), script.indexOf('  function goPage('));
const startDraftBody = script.split('\n').find(line => line.startsWith('  function startDraft('));
const lookupStart = script.indexOf('const human=state.variables.find', script.indexOf("document.addEventListener('click'"));
const lookupEnd = script.indexOf(';if(human)', lookupStart);
assert.ok(renderBody.includes('function renderPage()') && lookupStart > 0 && lookupEnd > lookupStart,
  'The source-rendering and manual-hit boundaries must remain identifiable.');
assert.ok(startDraftBody, 'The actual draft-entry boundary must remain identifiable.');
context.renderFixture = {words, anchor: {...sparse, docId: 'sct', page: 2}};
vm.runInContext(`(() => {
  const {words, anchor} = globalThis.renderFixture;
  const containsWord = globalThis.selectionHelpers.containsWord;
  const escape = value => String(value ?? '');
  const rangeNote = {innerHTML: '', visible: false, classList: {toggle(name, active) {
    if(name !== 'visible')throw Error('Unexpected range-note class.');rangeNote.visible = active;
  }}};
  const paper = {innerHTML: ''}, $ = id => id === 'graph-range-note' ? rangeNote : paper;
  const bi = (zh, en) => zh + ' / ' + en, button = (action, zh, en) => bi(zh, en);
  const data = {sourceUnits: []}, docId = 'sct', page = 2, selectedId = 'human-question';
  const v = {id: 'human-question', nameZh: '测试问题', nameEn: 'Synthetic question', status: 'changed', sourceAnchors: [anchor], editTargets: [anchor]};
  const state = {variables: [v]};
  const getName = () => v;
  let drag = null, draft = {kind: 'targets', targets: [anchor], a: anchor}, overviewRange = null;
  const clearSelection = () => {}, pageData = () => ({width: 200, height: 100, image: '/fixture.png', words});
  const current = () => v, anchorsFor = value => value.sourceAnchors, doc = () => ({shortTitle: 'SCT'});
  const rangesForUnit = () => [], unitVars = () => [];
  const renderDraft = () => {}, integrationChanged = editing => {globalThis.editDraftNotified = editing;};
  ${renderBody}
  ${startDraftBody}
  renderPage();
  globalThis.renderedSelection = paper.innerHTML;
  draft = null;renderPage();
  globalThis.renderedSavedSelection = paper.innerHTML;
  overviewRange = {id: v.id, targets: [
    {docId, page, start: 0, end: words.length, wordIds: [0, 4]},
    {docId: 'ntt', page, wordIds: [2]}, {docId, page: 3, wordIds: [3]}
  ]};
  renderPage();
  globalThis.renderedGraphRange = paper.innerHTML;
  globalThis.graphRangeNotice = {visible: rangeNote.visible, text: rangeNote.innerHTML};
  overviewRange = {id: v.id, exclusive: true, labelZh: '测试问题 · 情况二', labelEn: 'Synthetic question · Case two', targets: [
    {docId, page, start: 0, end: words.length, wordIds: [0]},
    {docId: 'ntt', page, wordIds: [2]}, {docId, page: 3, wordIds: [3]}
  ]};
  renderPage();
  globalThis.renderedExclusiveGraphRange = paper.innerHTML;
  globalThis.exclusiveGraphRangeNotice = {visible: rangeNote.visible, text: rangeNote.innerHTML};
  overviewRange = null;renderPage();
  globalThis.renderedClearedGraphRange = paper.innerHTML;
  globalThis.clearedGraphRangeNotice = {visible: rangeNote.visible, text: rangeNote.innerHTML};
  overviewRange = {id: v.id, exclusive: true, labelZh: '情况二', labelEn: 'Case two', targets: [{docId, page, wordIds: [0]}]};
  const savedState = JSON.stringify(state);
  startDraft('targets', {targets: [{docId, page, wordIds: [2]}]});
  globalThis.renderedEditedGraphRange = paper.innerHTML;
  globalThis.editDraftRangeCleared = overviewRange === null && !rangeNote.visible;
  draft.targets = [];renderPage();
  globalThis.renderedRemovedDraftTargets = paper.innerHTML;
  draft = null;renderPage();
  globalThis.renderedCancelledDraftTargets = paper.innerHTML;
  globalThis.draftPreviewPreservesSavedState = JSON.stringify(state) === savedState;
  globalThis.manualHit = i => {${script.slice(lookupStart, lookupEnd)};return human?.id;};
})();`, context);
for (let i = 0; i < words.length; i++) {
  const classes = context.renderedSelection.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))?.[1].split(/\s+/);
  assert.ok(classes, 'Every source word must render.');
  for (const name of ['focused', 'human', 'target', 'picked']) assert.equal(classes.includes(name), sparse.wordIds.includes(i),
    `Rendered ${name} uses the exact sparse membership for word ${i}.`);
  const savedClasses = context.renderedSavedSelection.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))[1].split(/\s+/);
  for (const name of ['focused', 'human', 'target']) assert.equal(savedClasses.includes(name), sparse.wordIds.includes(i),
    `Saved ${name} retains exact sparse membership after closing the edit draft.`);
  assert.equal(savedClasses.includes('picked'), false);
  const rangeClasses = context.renderedGraphRange.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))[1].split(/\s+/);
  assert.equal(rangeClasses.includes('target'), sparse.wordIds.includes(i) || [0, 4].includes(i),
    'Potential scope adds only explicit words on this document and page, leaving interval gaps unfilled.');
  for (const name of ['focused', 'human']) assert.equal(rangeClasses.includes(name), sparse.wordIds.includes(i),
    'Potential scope must not become source evidence or a focused source anchor.');
  const exclusiveClasses = context.renderedExclusiveGraphRange.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))[1].split(/\s+/);
  assert.equal(exclusiveClasses.includes('target'), i === 0,
    'A single-case range excludes the same owner\'s other amendments and ignores other files or pages.');
  for (const name of ['focused', 'human']) assert.equal(exclusiveClasses.includes(name), sparse.wordIds.includes(i),
    'A single-case target never becomes focused or human source evidence, nor removes the real source anchors.');
  assert.equal(exclusiveClasses.includes('picked'), false, 'Viewing a case does not fabricate an edit-draft selection.');
  const clearedClasses = context.renderedClearedGraphRange.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))[1].split(/\s+/);
  for (const name of ['focused', 'human', 'target']) assert.equal(clearedClasses.includes(name), sparse.wordIds.includes(i),
    'Clearing potential scope removes only its added highlights and retains the saved source and amendment membership.');
  const editedClasses = context.renderedEditedGraphRange.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))[1].split(/\s+/);
  assert.equal(editedClasses.includes('target'), i === 2, 'Entering target editing clears the old exclusive case and renders only the current draft targets.');
  for (const name of ['focused', 'human']) assert.equal(editedClasses.includes(name), sparse.wordIds.includes(i), 'Draft target previews preserve genuine source evidence.');
  const removedClasses = context.renderedRemovedDraftTargets.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))[1].split(/\s+/);
  assert.equal(removedClasses.includes('target'), false, 'Removing all draft targets does not leave the earlier case highlighted.');
  const cancelledClasses = context.renderedCancelledDraftTargets.match(new RegExp('<span[^>]*class="([^"]*)"[^>]*data-word="' + i + '"'))[1].split(/\s+/);
  assert.equal(cancelledClasses.includes('target'), sparse.wordIds.includes(i), 'Cancelling the draft restores the saved owner targets.');
  assert.equal(context.manualHit(i), sparse.wordIds.includes(i) ? 'human-question' : undefined,
    'Click lookup must not treat an unselected interior word as manually linked.');
}
assert.equal(context.graphRangeNotice.visible, true);
assert.ok(context.graphRangeNotice.text.includes('not evaluated against project answers'), 'Potential highlights keep their qualification visible.');
assert.equal(context.exclusiveGraphRangeNotice.visible, true);
assert.ok(context.exclusiveGraphRangeNotice.text.includes('测试问题 · 情况二') && context.exclusiveGraphRangeNotice.text.includes('Synthetic question · Case two'),
  'Exclusive highlighting names the selected instruction in both languages.');
assert.ok(context.exclusiveGraphRangeNotice.text.includes('not evaluated against project answers'), 'A selected instruction is not presented as an executed project amendment.');
assert.equal(context.clearedGraphRangeNotice.visible, false);
assert.equal(context.clearedGraphRangeNotice.text, '');
assert.equal(context.editDraftRangeCleared, true, 'Starting an edit exits presentation-only exclusive highlighting.');
assert.equal(context.draftPreviewPreservesSavedState, true, 'Adding or removing draft targets never rewrites saved review data.');
assert.equal(context.editDraftNotified, true, 'Entering an unsaved edit notifies the integration before saved state changes.');

const overviewSourceBody = script.slice(script.indexOf('  function overviewSource('), script.indexOf('  function overviewScopeTargets('));
assert.ok(overviewSourceBody.includes('function overviewSource('), 'The actual graph-to-source navigation boundary must remain identifiable.');
vm.runInContext(`(() => {
  const first = {docId: 'ntt', page: 1, wordIds: [0]}, second = {docId: 'ntt', page: 2, wordIds: [4]};
  const owner = {id: 'owner', nameZh: '测试问题', nameEn: 'Synthetic question', editTargets: [first, second]};
  const downstream = {docId: 'sct', page: 3, wordIds: [2]};
  const state = {variables: [owner]}, overviewFocus = owner.id, overviewActionId = 'case-two';
  const overviewBusinessView = {actions: [{id: overviewActionId, ownerId: owner.id,
    labelZh: '情况二', labelEn: 'Case two', targets: [{target: second, index: 1}]}]};
  let overviewRange, docId, navigation;
  const closeOverview = () => {}, selectQuestion = (id, navigate) => {navigation = {id, navigate};};
  const overviewScopeTargets = () => [first, second, downstream].map(target => ({target}));
  const anchorsFor = () => [], goPage = (page, anchor) => {navigation = {...navigation, docId, page, anchor};}, showQuestion = () => {};
  ${overviewSourceBody}
  overviewSource('action', 1);
  globalThis.exclusiveSourceNavigation = {range: overviewRange, navigation};
  overviewSource('target', 0);
  globalThis.normalSourceNavigation = {range: overviewRange, navigation};
})();`, context);
const exclusiveNavigation = plain(context.exclusiveSourceNavigation), normalNavigation = plain(context.normalSourceNavigation);
assert.equal(exclusiveNavigation.range.exclusive, true);
assert.deepEqual(exclusiveNavigation.range.targets, [{docId: 'ntt', page: 2, wordIds: [4]}],
  'The actual graph navigation builds an exclusive range from only the selected case.');
assert.equal(exclusiveNavigation.range.labelZh, '测试问题 · 情况二');
assert.equal(exclusiveNavigation.range.labelEn, 'Synthetic question · Case two');
assert.deepEqual(exclusiveNavigation.navigation, {id: 'owner', navigate: false, docId: 'ntt', page: 2,
  anchor: {docId: 'ntt', page: 2, wordIds: [4]}}, 'Navigation retains the original owner target index.');
assert.equal(normalNavigation.range.exclusive, false);
assert.equal(normalNavigation.range.targets.length, 3, 'Normal variable navigation restores the complete potential scope, including downstream passages.');
assert.equal(normalNavigation.navigation.page, 1);
console.log('PASS: selected-case source navigation and exact exclusive highlights, with normal scope/source membership restored after clearing.');

const quoteWords = [word('Drawings', 30, 20), word('“', 20, 20.804, 6), word('other', 70, 20)];
const quoteIds = plain(api.visualWordIds(quoteWords, [0, 1, 2]));
assert.deepEqual(quoteIds, [1, 0, 2], 'Small quote baseline differences must not reorder a line.');
assert.equal(api.selectedText(quoteWords, quoteIds), '“ Drawings other');

// A scroll can land between mouse down and mouse up on the floating toolbar.
// Exercise the actual scroll listener and setSelection: replacing its buttons loses that click.
const selectionBody = script.slice(script.indexOf('  function setSelection('), script.indexOf('  function pointOnPage('));
const scrollListener = script.split('\n').find(line => line.includes("$('reading-area').addEventListener('scroll'"));
assert.ok(selectionBody.includes('function setSelection(') && scrollListener, 'Selection toolbar boundaries must remain identifiable.');
context.toolbarWords = words;
vm.runInContext(`(() => {
  const words = globalThis.toolbarWords, {visualWordIds, selectedText} = globalThis.selectionHelpers;
  let selection = null, draft = null, capture = null, generation = 0, markup = '', nodes = [], scroll;
  const actions = {dataset: {}, get innerHTML(){return markup;}, set innerHTML(value){
    markup = value;generation++;nodes = [...value.matchAll(/data-action="([^"]+)"/g)].map(match => ({action: match[1], generation}));
  }, querySelector(selector){const action = selector.match(/data-action="([^"]+)"/)?.[1];return nodes.find(node => node.action === action) || null;}};
  const bar = {hidden: true, style: {}, offsetHeight: 50, querySelector: () => actions}, preview = {textContent: ''};
  const area = {addEventListener: (event, listener) => {if(event === 'scroll')scroll = listener;}};
  const paper = {querySelector: () => ({getBoundingClientRect: () => ({left: 20, bottom: 100 - generation})})};
  const $ = id => ({selectionbar: bar, 'selection-preview': preview, paper, 'reading-area': area})[id];
  const pageData = () => ({words}), docId = 'sct', page = 2, innerWidth = 1000, innerHeight = 800;
  const paintSelection = () => {}, clearSelection = () => {selection = null;bar.hidden = true;};
  const button = action => '<button data-action="' + action + '"></button>';
  const requestAnimationFrame = callback => callback();
  ${selectionBody}
  ${scrollListener}
  globalThis.toolbarFixture = {select: setSelection, scroll: () => scroll(),
    capture: value => {capture = value;}, button: action => actions.querySelector('[data-action="' + action + '"]'),
    preview: () => preview.textContent};
})();`, context);
const toolbar = context.toolbarFixture;
toolbar.select([1, 4]);
const pressedLink = toolbar.button('selection-link');
assert.ok(pressedLink);
toolbar.scroll();
assert.equal(toolbar.button('selection-link'), pressedLink,
  'Scroll between mouse down and up must preserve the Link button node so its click reaches the chooser.');
toolbar.select([1, 5]);
assert.equal(toolbar.button('selection-link'), pressedLink, 'Changing the selected words also retains the same action buttons.');
assert.equal(toolbar.preview(), 'Side1\nSide3', 'The preview still updates when the toolbar buttons are retained.');
toolbar.capture('target');toolbar.select([1]);
const pressedUse = toolbar.button('use-selection');
assert.ok(pressedUse);
assert.equal(toolbar.button('selection-link'), null, 'Entering capture mode changes the available actions.');
toolbar.scroll();
assert.equal(toolbar.button('use-selection'), pressedUse, 'Scrolling must also retain the capture button node.');
toolbar.capture('a');toolbar.select([4]);
assert.equal(toolbar.button('use-selection'), pressedUse, 'The same capture action stays mounted across capture destinations.');
toolbar.capture(null);toolbar.select([4]);
assert.ok(toolbar.button('selection-link'));
assert.equal(toolbar.button('use-selection'), null, 'Leaving capture mode restores the normal selection actions.');
console.log('PASS: scroll and selection updates preserve floating action nodes until the capture mode changes.');

if (args.length) {
  const view = JSON.parse(fs.readFileSync(args[1], 'utf8'));
  const pageWords = view.documents.sct.pages[1].words;
  const from = {x: 455, y: 105}, to = {x: 560, y: 205};
  const expected = [...Array.from({length: 5}, (_, i) => i + 21),
    ...Array.from({length: 12}, (_, i) => i + 59)];
  const selected = plain(api.wordsInBox(pageWords, from, to));
  assert.deepEqual(selected, expected, 'SCT page 2 selects only the right-hand note.');
  assert.deepEqual(plain(api.wordsInBox(pageWords, to, from)), expected);
  assert.equal(api.selectedText(pageWords, selected),
    '(Alternative (a) –\nfor tenders\nadopting electronic\ndissemination of\ntender documents\nwith BQ issued in\nL10Pro system)');
  const anchor = {start: 21, end: 71, wordIds: selected.filter(i => i !== 60)};
  for (let i = 26; i <= 58; i++) assert.equal(api.containsWord(anchor, i), false,
    'Main-body text between sidebar word IDs must stay unselected.');
  assert.equal(api.containsWord(anchor, 60), false, 'A manually removed sidebar word stays removed.');
  assert.equal(api.containsWord(anchor, 59), true);
  assert.equal(api.containsWord(anchor, 61), true);
  console.log('PASS: real SCT page 2 sidebar (17 exact words, forward/reverse, no main-body leakage, sparse removal).');
}
console.log('PASS: geometry self-check (columns, thin drag, visual order, quote jitter, sparse render/hit, legacy anchors).');
