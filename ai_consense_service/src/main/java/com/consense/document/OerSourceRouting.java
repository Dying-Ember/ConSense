package com.consense.document;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** General source classification; filenames and isolated body keywords are only candidates. */
public final class OerSourceRouting {
    private static final int IDENTITY_BLOCK_LIMIT=1024,IDENTITY_TEXT_LIMIT=131072;
    private static final List<String> MODULES = Collections.unmodifiableList(Arrays.asList("drafting", "vetting", "advisory"));
    private static final List<String> PURPOSES = Arrays.asList("standard_template", "project_document", "reference_standard",
            "project_fact", "package_manifest", "evaluation_answer", "unknown");
    private final Map<String, FixedSource> fixedSources = new HashMap<>();

    public OerSourceRouting() { this(Collections.emptyList()); }
    public OerSourceRouting(List<FixedSource> fixedSources) {
        for (FixedSource source : fixedSources) {
            String sha = validSha(source.sourceSha256);
            if (sha == null || !PURPOSES.contains(source.sourcePurpose)) throw new IllegalArgumentException("Invalid fixed source identity");
            if ("standard_template".equals(source.sourcePurpose) && !Arrays.asList("NTT", "SCT", "SCC").contains(source.kind))
                throw new IllegalArgumentException("Only NTT/SCT/SCC can be registered as a drafting standard template");
            if (this.fixedSources.put(sha, source) != null) throw new IllegalArgumentException("Duplicate fixed source SHA");
        }
    }

    /** Identity only: a drawing label or native format must precede every detail parser. */
    public static boolean isDrawing(String documentScope, String fileName, byte[] bytes) {
        if ("drawing".equals(documentScope)) return true;
        String header = new String(bytes, 0, Math.min(bytes.length, 64), java.nio.charset.StandardCharsets.US_ASCII);
        if (header.matches("(?s)^AC10[0-9]{2}.*")) return true;
        if (header.startsWith("AutoCAD Binary DXF")
                || header.matches("(?s)^\\s*0\\s+SECTION\\s+.*")) return true;

        return false;
    }

    public static List<OerDocumentIdentity.Evidence> drawingMetadata(byte[] bytes) {
        List<OerDocumentIdentity.Evidence> hints = new java.util.ArrayList<>();
        if (bytes == null || bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return hints;
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
            org.apache.pdfbox.pdmodel.PDDocumentInformation info = pdf.getDocumentInformation();
            if (drawingLabel(info.getTitle())) hints.add(new OerDocumentIdentity.Evidence("pdf_metadata_candidate", "metadata/title", info.getTitle()));
            if (drawingLabel(info.getSubject())) hints.add(new OerDocumentIdentity.Evidence("pdf_metadata_candidate", "metadata/subject", info.getSubject()));
        } catch (java.io.IOException invalidPdf) { /* Actual parser records malformed source failures. */ }
        return hints;
    }

    private static boolean drawingLabel(String label) {
        return label != null && label.trim().matches("(?i)(?:engineering |technical |施工)?(?:drawings?|图纸|圖紙)");
    }

    public OerDocumentIdentity route(String fileName, String sourceSha256, DocumentParser.ParsedDocument parsed) {
        return route(fileName,sourceSha256,parsed == null ? null : parsed.getSourceSha256(),
                parsed == null ? "" : parsed.getText(),parsed == null ? Collections.emptyList() : parsed.getBlocks(),
                parsed == null ? null : parsed.getRoutingObservation());
    }

    /** Reclassify the exact persisted parser fields; this never invokes a parser or invents parse coverage. */
    public OerDocumentIdentity routeRetained(String fileName,String sourceSha256,String text,List<DocumentBlock> blocks) {
        return route(fileName,sourceSha256,sourceSha256,text,blocks,null);
    }

    private OerDocumentIdentity route(String fileName,String sourceSha256,String parsedSha,String text,
            List<DocumentBlock> blocks,com.fasterxml.jackson.databind.JsonNode routingObservation) {
        OerDocumentIdentity result = new OerDocumentIdentity();
        String sha = validSha(sourceSha256); result.setSourceSha256(sha);
        if (sha == null || (parsedSha != null && !sha.equalsIgnoreCase(parsedSha))) {
            result.setIdentityStatus("source_identity_mismatch");
            result.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("byte_identity", null, "Missing/invalid original SHA or parsed original-byte identity differs"));
            return result;
        }
        FixedSource fixed = fixedSources.get(sha);
        if (fixed != null) {
            result.setKind(fixed.kind); result.setSourcePurpose(fixed.sourcePurpose); result.setRole(fixed.role);
            result.setVersion(fixed.version); result.setIdentityStatus("sha_verified");
            result.setEligibleModules(eligible(fixed.kind, fixed.sourcePurpose));
            result.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("fixed_source_sha256", null, "Exact original bytes match the supplied source manifest"));
            return result;
        }
        String title = firstTitle(text,blocks), content = text == null ? "" : text;
        String titleKind=titleKind(title);
        OerDocumentIdentity.Evidence preliminaries=nativePreliminaries(titleKind,title,sha,blocks);
        if(preliminaries!=null)titleKind="PRE";
        OerDocumentIdentity.Evidence projectIdentifier = projectIdentifier(content,sha,blocks,titleKind,preliminaries!=null);
        String appendixLocation = (titleKind == null || "SCT".equals(titleKind)) ? nativeTenderAppendix(sourceSha256,text,blocks) : null;
        if (appendixLocation != null) titleKind = "SCT";
        if (titleKind != null) {
            if(preliminaries!=null)result.getIdentityEvidence().add(preliminaries);
            result.getIdentityEvidence().add(appendixLocation == null
                    ? new OerDocumentIdentity.Evidence("native_or_parsed_title", "first_content_block", "Title supports " + titleKind + "; content quality and applicability remain separate")
                    : new OerDocumentIdentity.Evidence("native_tender_appendix", appendixLocation, "Native title/header declares an appendix to Special Conditions of Tender with assessment and submission provisions; project adoption remains separate"));
            String purpose = "unknown";
            if (Arrays.asList("NTT", "SCT", "SCC").contains(titleKind)) {
                if (appendixLocation != null || projectIdentifier != null) purpose = "project_document";
                else result.getCandidates().add(new OerDocumentIdentity.Candidate(titleKind, "standard_template", "An unfilled title alone does not establish a standard template"));
            } else if (Arrays.asList("AA", "FT").contains(titleKind)) purpose = "project_document";
            else if (Arrays.asList("APB", "APU").contains(titleKind)
                    && title.matches("(?is)^\\s*(?:appendix\\s+[a-z0-9]+\\s+)?list of (?:contract drawings|reference drawings\\s*(?:/|and)\\s*documents)(?:\\s.*)?$")) purpose = "project_document";
            else if ("PRE".equals(titleKind) && projectIdentifier != null) purpose = "project_document";
            else if (Arrays.asList("GCC", "GS").contains(titleKind)) purpose = "reference_standard";
            else if ("GCT".equals(titleKind) && content.matches("(?is).*\\b(?:tenderer|tender|contract)\\b.*")) purpose = "project_document";
            else if (Arrays.asList("BQ", "SOR", "GENERAL_SUMMARY").contains(titleKind)
                    && Pattern.compile("(?i)\\b(?:quantity|rate|amount|price|pricing|bill|total|sum)\\b").matcher(content).find()) purpose = "project_document";
            // PRE is deliberately not caught by technical-PSS title rules.
            else if ("WORKS_PSS".equals(titleKind) && Pattern.compile("(?i)\\b(?:materials|workmanship|construction|installation|technical)\\b").matcher(content).find()) purpose = "project_document";
            if (!"unknown".equals(purpose)) {
                result.setKind(titleKind); result.setSourcePurpose(purpose); result.setIdentityStatus("content_supported");
                result.setRole("reference_standard".equals(purpose) ? "standard" : "tender");
                result.setEligibleModules(eligible(titleKind, purpose));
            } else result.getCandidates().add(new OerDocumentIdentity.Candidate(titleKind, purpose, "Purpose needs actual project/standard identity evidence"));
        }
        if ("unknown".equals(result.getIdentityStatus()) && routingObservation != null) {
            if (routingObservation.path("nonDrawingFormatVerified").asBoolean()) {
                result.setIdentityStatus("format_supported_non_drawing");
                if (projectIdentifier != null) {
                    result.setSourcePurpose("project_document"); result.setRole("tender"); result.setIdentityStatus("content_supported");
                    result.setEligibleModules(MODULES);
                }
            }
            else if (titleKind != null) result.setIdentityStatus("content_supported_non_drawing");
        }
        if (projectIdentifier != null && "content_supported".equals(result.getIdentityStatus())
                && "project_document".equals(result.getSourcePurpose())) result.getIdentityEvidence().add(projectIdentifier);
        String filenameKind = filenameCandidate(fileName);
        if (filenameKind != null && !Objects.equals(filenameKind, result.getKind()))
            result.getCandidates().add(new OerDocumentIdentity.Candidate(filenameKind, "unknown", "Filename is a clue, never the adoption or module decision"));
        if (result.getIdentityEvidence().isEmpty()) result.getIdentityEvidence().add(new OerDocumentIdentity.Evidence("unresolved", null, "No sufficient source identity evidence; candidates and original source remain available"));
        return result;
    }

    private static String nativeTenderAppendix(String sourceSha256,String sourceText,List<DocumentBlock> blocks) {
        if (blocks == null || validSha(sourceSha256) == null) return null;
        String opening = firstTitle(sourceText,blocks).replaceAll("\\s+", " ").trim();
        if (opening.matches("(?i)^(?:(?:sample|model|example|suggested) (?:answer|report)|comments on tender documentation|checklist for|contractual advice|flowchart).*")) return null;
        Pattern parent = Pattern.compile("(?i)^appendix\\h+[a-z0-9]+(?:[.-][a-z0-9]+)*\\h+to\\s*special\\h+conditions?\\h+of\\h+tender\\b");
        String declared = null; boolean firstBody = true; StringBuilder body = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (block == null || block.getSourcePosition() == null || block.getEffectiveText() == null) continue;
            com.fasterxml.jackson.databind.JsonNode position = block.getSourcePosition();
            if (!"docx_native".equals(position.path("kind").asText()) || !sourceSha256.equals(position.path("sourceSha256").asText())
                    || validSha(position.path("partSha256").asText()) == null || position.path("nodePath").asText().isEmpty()) continue;
            String part = position.path("partName").asText(), text = block.getEffectiveText().replaceAll("\\s+", " ").trim();
            if ("word/document.xml".equals(part) && !text.isEmpty()) {
                if (firstBody && parent.matcher(text).find()) declared = block.getLocation();
                firstBody = false; body.append(text).append('\n');
            } else if (part.matches("word/header[0-9]+\\.xml") && parent.matcher(text).find()) declared = block.getLocation();
        }
        String content = body.toString();
        return declared != null
                && Pattern.compile("(?i)\\b(?:tenderers?|technical proposals?)\\b").matcher(content).find()
                && Pattern.compile("(?i)\\b(?:assessment standards?|marking scheme|scores?|scoring)\\b").matcher(content).find()
                && Pattern.compile("(?i)\\b(?:submission requirements?|submit|submitted|submitting)\\b").matcher(content).find()
                && Pattern.compile("(?i)\\b(?:shall|must|will)\\b").matcher(content).find() ? declared : null;
    }

    private static List<String> eligible(String kind, String purpose) {
        if (Arrays.asList("BQ", "SOR", "GENERAL_SUMMARY", "WORKS_PSS", "SL_NON_TECHNICAL").contains(kind)
                || "evaluation_answer".equals(purpose)) return Collections.emptyList();
        if ("standard_template".equals(purpose)) return Collections.singletonList("drafting");
        if ("unknown".equals(purpose)) return null;
        return MODULES;
    }
    private static String validSha(String value) { return value != null && value.matches("(?i)[0-9a-f]{64}") ? value.toLowerCase(Locale.ROOT) : null; }
    private static OerDocumentIdentity.Evidence nativePreliminaries(String declaredKind,String title,String sha,List<DocumentBlock> blocks) {
        if(blocks==null||validSha(sha)==null||declaredKind!=null)return null;
        if(title.replaceAll("\\s+"," ").trim().matches("(?i)^(?:(?:sample|model|example|suggested) (?:answer|report)|comments on tender documentation|checklist for|contractual advice|flowchart).*"))return null;
        String location=null;boolean projectHeading=false,worksHeading=false;int count=0,chars=0;
        for(DocumentBlock block:blocks) {
            if(++count>IDENTITY_BLOCK_LIMIT)break;
            if(!boundBody(block,sha))continue;
            String text=block.getEffectiveText();if(text==null)continue;
            if((long)chars+text.length()>IDENTITY_TEXT_LIMIT)break;chars+=text.length();
            for(String line:text.split("\\R")) {
                String value=line.replaceAll("\\h+"," ").trim();
                if(value.matches("(?i)^PRE[.]B1 PROJECT PARTICULARS(?: [0-9]+)?$"))location=block.getLocation();
                if(value.matches("(?i)^PRE[.]B1[.]0(?:10|20)(?:[.]P)? (?:THE PROJECT|THE CONTRACT MANAGER)(?: [0-9]+)?$"))projectHeading=true;
                if(value.matches("(?i)^PRE[.]B2 THE WORKS(?: [0-9]+)?$"))worksHeading=true;
            }
        }
        return location!=null&&projectHeading&&worksHeading?new OerDocumentIdentity.Evidence("native_preliminaries_heading",location,
                "Source-bound native PRE project-particulars, project and works headings; filename, project adoption and completeness are separate"):null;
    }
    private static boolean boundBody(DocumentBlock block,String sha) {
        if(block==null||block.getSourcePosition()==null||block.getLocation()==null||block.getLocation().isEmpty())return false;
        com.fasterxml.jackson.databind.JsonNode p=block.getSourcePosition();
        return "docx_native".equals(p.path("kind").asText())&&sha.equals(p.path("sourceSha256").asText())
                &&"word/document.xml".equals(p.path("partName").asText())&&validSha(p.path("partSha256").asText())!=null&&!p.path("nodePath").asText().isEmpty();
    }
    private static OerDocumentIdentity.Evidence projectIdentifier(String text, String sourceSha256, List<DocumentBlock> blocks,String declaredKind,boolean nativePreRequired) {
        String opening = firstTitle(text,blocks).replaceAll("\\s+", " ").trim();
        if (opening.matches("(?i)^(?:(?:sample|model|example|suggested) (?:answer|report)|comments on tender documentation|checklist for|contractual advice|flowchart).*")) return null;
        if (blocks != null && validSha(sourceSha256) != null
                && Arrays.asList("NTT", "SCT", "SCC", "PRE").contains(declaredKind)) {
            Pattern declaration = Pattern.compile("(?i)^\\h*contract\\h*(?:no\\.?|number)(?:\\h*[:：#]\\h*|\\h+)(?=[a-z0-9/._-]*[0-9])[a-z0-9][a-z0-9/._-]{2,}\\h+\\(the\\h+(?:\"Contract\"|“Contract”)\\)\\h*$");
            int count=0,chars=0;
            for (DocumentBlock block : blocks) {
                if("PRE".equals(declaredKind)&&++count>IDENTITY_BLOCK_LIMIT)break;
                if (block == null || block.getSourcePosition() == null || block.getEffectiveText() == null
                        || block.getLocation() == null || block.getLocation().isEmpty()) continue;
                if("PRE".equals(declaredKind)){if((long)chars+block.getEffectiveText().length()>IDENTITY_TEXT_LIMIT)break;chars+=block.getEffectiveText().length();}
                com.fasterxml.jackson.databind.JsonNode position = block.getSourcePosition();
                if ("docx_native".equals(position.path("kind").asText()) && sourceSha256.equals(position.path("sourceSha256").asText())
                        && "word/document.xml".equals(position.path("partName").asText()) && validSha(position.path("partSha256").asText()) != null
                        && !position.path("nodePath").asText().isEmpty() && declaration.matcher(block.getEffectiveText()).matches())
                    return new OerDocumentIdentity.Evidence("native_contract_definition", block.getLocation(), "Source-bound native body declares the numbered Contract; project adoption and content completeness remain separate");
            }
        }
        if(nativePreRequired)return null;
        if (Pattern.compile("(?im)^\\h*(?:contract|tender|project)\\h*(?:no\\.?|number|reference|ref\\.?|title)(?:\\h*[:：#]\\h*|\\h+)(?!(?:not provided|to be completed|insert|unknown|n/a)\\b)(?![.＿_\\- ]*(?:$|\\[))(?:[\\p{L}\\p{N}][\\p{L}\\p{N}/._ -]{2,})\\h*$").matcher(text).find()) return new OerDocumentIdentity.Evidence("observed_project_identifier", "observed_content", "Labelled project identifier in the observed text; project adoption remains separate");
        return null;
    }
    private static String firstTitle(String sourceText,List<DocumentBlock> blocks) {
        StringBuilder title = new StringBuilder(); int lines = 0;
        if (blocks != null) for (DocumentBlock block : blocks) {
            String text = block.getText();
            if (text != null) for (String line : text.split("\\n")) if (!line.trim().isEmpty()) {
                title.append(line.trim()).append('\n'); if (++lines >= 4) return title.toString();
            }
        }
        if (lines > 0) return title.toString();
        String text = sourceText;
        if (text != null) for (String line : text.replaceFirst("(?s)^--- P\\d+ ---\\s*", "").split("\\n")) if (!line.trim().isEmpty()) {
            title.append(line.trim()).append('\n'); if (++lines >= 4) break;
        }
        return title.toString();
    }
    private static String titleKind(String title) {
        if (title.matches("(?is)^\\s*(?:appendix\\s+[a-z0-9]+\\s+)?list of reference drawings\\s*(?:/|and)\\s*documents(?:\\s.*)?$")) return "APU";
        // A PRE cover commonly prints PARTICULAR SPECIFICATION and PRELIMINARIES on separate lines.
        for (String line : title.split("\\n")) { String kind = titledKind(line); if (kind != null) {
            if ("WORKS_PSS".equals(kind) && Pattern.compile("(?im)^\\s*preliminaries(?:\\s+specification)?\\s*$").matcher(title).find()) return "PRE";
            return kind;
        } }
        return null;
    }
    private static String filenameCandidate(String name) {
        if (name == null) return null;
        String value = name.replace('_', ' ').replace('-', ' ').replaceFirst("(?i)\\.[a-z0-9]+$", "");
        for (String kind : Arrays.asList("NTT", "SCT", "SCC", "GCT", "GCC", "PRE", "GS", "SL", "BQ", "SOR"))
            if (Pattern.compile("(?i)(?:^|\\W)" + kind + "(?:$|\\W)").matcher(value).find()) return kind;
        return titledKind(value);
    }
    private static String titledKind(String title) {
        String value = title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        value = value.trim().replaceFirst("^(?:[0-9]+[. )-]*|appendix [a-z0-9]+[. :-]*)", "").trim();
        if (value.matches("(?:articles? of agreement|form of agreement)(?: \\(aa\\))?")) return "AA";
        if (value.matches("form of tender(?: \\(ft\\))?")) return "FT";
        if (value.matches("list of contract drawings")) return "APB";
        if (value.matches("list of reference drawings and documents")) return "APU";
        if (value.matches("(?:notes?|notice) to tenderers?(?: \\(ntt\\))?")) return "NTT";
        if (value.matches("special conditions? of tender(?: \\(sct\\))?")) return "SCT";
        if (value.matches("special conditions? of contract(?: \\(scc\\))?")) return "SCC";
        if (value.matches("general conditions? of tender(?: \\(gct\\))?")) return "GCT";
        if (value.matches("general conditions? of contract(?: \\(gcc\\))?")) return "GCC";
        if (value.matches("general specification(?: for [a-z /-]+)?")) return "GS";
        if (value.matches("specification library(?: [0-9]{4})?")) return "SL";
        if (value.matches("(?:particular specification[-: ]*)?preliminaries(?: specification)?")) return "PRE";
        if (value.matches("bills? of quantities")) return "BQ";
        if (value.matches("schedule of rates")) return "SOR";
        if (value.matches("general summary")) return "GENERAL_SUMMARY";
        if (value.matches("particular specification(?:[-: ]+(?:architectural|structural|building services|civil|foundation|demolition|drainage|external|landscape)(?: works)?)?")) return "WORKS_PSS";
        return null;
    }

    /** Supplied trusted source registry, versioned outside this classifier; no project hashes embedded in code. */
    public static final class FixedSource {
        private final String sourceSha256, kind, sourcePurpose, role, version;
        public FixedSource(String sourceSha256, String kind, String sourcePurpose, String role, String version) {
            this.sourceSha256 = sourceSha256; this.kind = kind; this.sourcePurpose = sourcePurpose; this.role = role; this.version = version;
        }
    }
}
