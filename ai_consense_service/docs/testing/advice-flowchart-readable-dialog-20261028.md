# Advice flowchart reading check

Date: 2026-10-28 (Asia/Shanghai).

An actual saved Clause 17.1 answer showed that enlarging a diagram inside the narrow chat column left its starting node outside the initial viewport. The fitted version reduced its labels to an impractical reading size.

The existing Enlarge flowchart action now opens the existing application modal at a wider size. The original rendered SVG moves between the inline view and the dialog without another Mermaid generation. Closing the dialog retains the answer, quotations and diagram source. No model request is made by changing display size.

This delivery also includes the previously tested Mermaid label correction: after SVG sanitization, only literal text nodes decode the three escaped characters produced by the renderer. Attributes, links and active markup remain excluded.

Validation:

- The new dialog regression first failed against the inline enlargement implementation.
- Eleven renderer and component tests passed, including the installed Mermaid parser, malicious markup, escaped labels, failed rendering and stale results.
- Vue type checking passed.
- The embedded browser selected the existing two-answer conversation and refreshed it. Both saved diagrams restored.
- The real second diagram opened in the wide modal with readable node labels and visible initial branches. Fit to panel closed the modal and retained both answers and both diagram controls.
- The served frontend component matches the checked source. A screenshot and the replaced component are retained in the ignored local acceptance directory.

The saved answers remain explicitly labeled as draft flowcharts. This check establishes display and saved-history behavior, not contractual correctness, complete evidence, an interrupted-generation recovery or complete Vetting acceptance.
