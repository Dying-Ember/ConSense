# OCR diagnostics display fixture

`inspection-ocr-diagnostics.actual.json` stores the unmodified original `line_stage_probe.json` payload from the completed SL1006 B diagnostic and the original APC19 v4 `candidate_tables.json` envelope. It is a display fixture, not an inspection API source-binding proof, new recognition, gold answer, corrected source, applied table or human confirmation. Root's backend sidecar separately validates source/dataset/page/artifact identity before exposing these records.

The focused SSR check renders the real Vue component. It verifies that the stage display retains the original 180-degree classification, 0.91515 classification confidence and 0.49389 rejected recognition, limits the first page to 20 of 51 lines, and does not infer stages when absent. It also checks that the fused `NODESCRIPTION AND DATE` source remains visible with both cell-overlap fractions, an unknown NO cell and `nativeCell=false`. The revision header's table selection is narrowed only for this test; actual cell data are not changed.

The fixture lives outside public assets and is not fetched by the product. No API, NN, OCR or extraction is run by the display check. Source content is rendered as escaped text.
