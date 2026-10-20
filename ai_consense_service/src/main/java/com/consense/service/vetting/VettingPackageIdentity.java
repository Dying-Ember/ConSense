package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-only identity coordination. Missing observations and unadopted identity never mean PASS. */
public final class VettingPackageIdentity {
    public static final String POLICY="package-identity-coordination-v1";
    public static final String CONTEXT="packageIdentityContext";
    private static final String LABEL="contract\\h+(?:no\\.?|number|title)|project\\h+(?:name|title)|合同(?:編號|编号|名稱|名称)|(?:項目|项目)(?:名稱|名称)";
    private static final String SEPARATOR="(?:\\h*[:：=]\\h*|\\h+)";
    private static final Pattern DECLARATION=Pattern.compile("(?im)^([^\\r\\n]*?)("+LABEL+")"+SEPARATOR+"([^\\r\\n]*)$");
    private static final Pattern ANOTHER_LABEL=Pattern.compile("(?i)(?:"+LABEL+")"+SEPARATOR);
    private static final Pattern EXTERNAL=Pattern.compile("(?i)\\b(?:another|other|external|reference|refer(?:red)?\\h+to|see|under)\\b|其他|另一|參見|参见|參考|参考");
    private static final Pattern EXAMPLE=Pattern.compile("(?i)^(?:standard(?:\\h+form)?|template|example|sample|specimen|edition|標準|标准|範本|范本|示例|版本)(?:\\h*[:：-]?)$");
    private static final Pattern EXTERNAL_HEADING=Pattern.compile("(?i)^(?:external\\h+references?|(?:other|another)\\h+(?:contracts?|projects?)|外部引用|其他合同|其他項目|其他项目)(?:\\h*[:：-]?)$");
    private static final Pattern PLACEHOLDER=Pattern.compile("(?i)^(?:unknown|n/?a|tbd|to\\h+be\\h+(?:confirmed|completed)|[\\s_.*?—–-]+|\\[[^]]*]|<[^>]*>)$");
    private VettingPackageIdentity(){}

    public static final class Review {
        private final List<VettingRuleEngine.Output> outputs;
        private final String ledgerJson;
        private Review(List<VettingRuleEngine.Output> values,ObjectNode ledger){outputs=Collections.unmodifiableList(new ArrayList<>(values));ledgerJson=JsonUtils.write(ledger);}
        public List<VettingRuleEngine.Output> outputs(){return outputs;}
        public JsonNode ledger(){return JsonUtils.parse(ledgerJson);}
    }
    private static final class Declaration {
        final VettingRuleEngine.Source source;final DocumentBlock block;final String field,raw,classification,reason,occurrence;
        final int start,end;
        Declaration(VettingRuleEngine.Source s,DocumentBlock b,String f,String value,String kind,String why,int from,int to,String id){source=s;block=b;field=f;raw=value;classification=kind;reason=why;start=from;end=to;occurrence=id;}
        ObjectNode json(){ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("documentId",source.getId());out.put("sourceHash",source.getHash());out.put("blockId",block.getId());out.put("anchor",block.getLocation());out.put("field",field);out.put("rawValue",raw);out.put("comparisonValue",comparison(raw));out.put("classification",classification);out.put("scopeStatus","unknown");out.put("reason",reason);out.put("startOffset",context(block).path("startOffset").asInt()+start);out.put("endOffset",context(block).path("startOffset").asInt()+end);out.put("offsetUnit","utf16");out.put("quote",block.getText().substring(start,end));out.put("occurrenceId",occurrence);out.set("sourcePosition",block.getSourcePosition());out.set("context",context(block).deepCopy());out.put("physicalWordPageStatus","unknown");return out;}
        VettingRuleEngine.Evidence evidence(){return new VettingRuleEngine.Evidence(source.getId(),source.getFileKey(),source.getFileName(),source.getHash(),block.getId(),block.getLocation(),block.getPageNo(),block.getLocation(),block.getText().substring(start,end),block.getBbox()==null?null:block.getBbox().clone());}
    }
    public static Review review(List<VettingRuleEngine.Source> sources,JsonNode authority) {
        ObjectNode ledger=JsonUtils.mapper().createObjectNode();ledger.put("policy",POLICY);ledger.put("comparisonPolicy","unicode-whitespace-collapse-only-v1");ledger.put("status","executed_with_explicit_unknowns");ledger.set("authority",authority==null?JsonUtils.mapper().nullNode():authority.deepCopy());
        ledger.putArray("declarations");ledger.putArray("documents");ledger.putArray("unknowns");
        List<Declaration> declarations=new ArrayList<>();List<ObjectNode> documentResults=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(sources!=null)for(VettingRuleEngine.Source source:sources){if(source==null)continue;int before=declarations.size();
            if(source.getBlocks()!=null)for(DocumentBlock block:source.getBlocks())if(block!=null&&block.getText()!=null){
                Matcher matcher=DECLARATION.matcher(block.getText());boolean found=false;
                while(matcher.find()){found=true;String label=matcher.group(2),raw=matcher.group(3),prefix=comparison(matcher.group(1));String field=label.toLowerCase(Locale.ROOT).matches(".*(?:no\\.?|number)$")||label.endsWith("編號")||label.endsWith("编号")?"number":"title";
                    JsonNode context=context(block);String kind="unknown",reason="own_identity_scope_unproven";
                    if(!"source_bound".equals(context.path("status").asText()))reason="source_context_unbound";
                    else if(block.getSourcePosition()!=null&&"docx_native".equals(block.getSourcePosition().path("kind").asText())&&!"observed_loaded_native_root".equals(context.path("storyStatus").asText()))reason="native_story_binding_unknown";
                    else if(!(matcher.start()>0||context.path("selectedStartsAtSourceLineBoundary").asBoolean())||!(matcher.end()<block.getText().length()||context.path("selectedEndsAtSourceLineBoundary").asBoolean()))reason="selected_declaration_line_not_closed";
                    else if(EXTERNAL.matcher(prefix).find()){kind="external_reference";reason="explicit_external_reference";}
                    else if("example".equals(context.path("documentScope").asText())||"standard".equals(context.path("role").asText())||EXAMPLE.matcher(prefix).matches()||exampleHeading(block.getText().substring(0,matcher.start()))){kind="template_or_example";reason="explicit_template_example_or_standard_role";}
                    else if("bound_native_outline_exclusion".equals(context.path("sourceScopeExclusion").path("status").asText())){kind=context.path("sourceScopeExclusion").path("classification").asText();reason="explicit_bound_native_outline_exclusion";}
                    else if(!"tender".equals(context.path("role").asText()))reason="package_role_unproven";
                    else if(PLACEHOLDER.matcher(comparison(raw)).matches()||comparison(raw).isEmpty())reason="value_missing_or_placeholder";
                    else if(ANOTHER_LABEL.matcher(raw).find())reason="multiple_labels_without_bound_value_ranges";
                    else if(prefix.isEmpty()||"this".equalsIgnoreCase(prefix)||"本".equals(prefix)){kind="identity_candidate";reason="explicit_label_value_with_own_package_scope_unknown";}
                    String identity=occurrence(source,block,matcher.start(),matcher.end());if(seen.add(identity))declarations.add(new Declaration(source,block,field,raw,kind,reason,matcher.start(),matcher.end(),identity));
                }
                if(!found){ObjectNode unknown=JsonUtils.mapper().createObjectNode();unknown.put("documentId",source.getId());unknown.put("blockId",block.getId());unknown.put("status","unknown");unknown.put("reason","no_explicit_labelled_identity_in_accepted_range");ledger.withArray("unknowns").add(unknown);}
            }
            ObjectNode document=JsonUtils.mapper().createObjectNode();document.put("documentId",source.getId());document.put("sourceHash",source.getHash());document.put("observations",declarations.size()-before);ObjectNode fields=document.putObject("fields");
            for(String field:Arrays.asList("number","title")){List<String> values=new ArrayList<>();for(Declaration d:declarations)if(d.source==source&&field.equals(d.field)&&"identity_candidate".equals(d.classification))values.add(comparison(d.raw));ObjectNode assessment=fields.putObject(field);JsonNode adopted=authority==null?JsonUtils.mapper().nullNode():authority.path("core").path("fields").path(field);boolean eligible=adopted.path("eligible").asBoolean()&&adopted.path("value").isTextual()&&!comparison(adopted.path("value").asText()).isEmpty();boolean difference=new HashSet<>(values).size()>1||eligible&&values.stream().anyMatch(value->!value.equals(comparison(adopted.path("value").asText())));assessment.put("status",values.isEmpty()?"unknown_no_own_declaration":difference?"candidate_difference_requires_review":"candidate_agreement_scope_unknown");assessment.put("ownDeclarations",0);assessment.put("candidateDeclarations",values.size());assessment.put("scopeStatus","unknown");assessment.put("authorityStatus",eligible?"eligible":"unknown");}
            documentResults.add(document);
        }
        for(Declaration d:declarations)ledger.withArray("declarations").add(d.json());for(ObjectNode document:documentResults)ledger.withArray("documents").add(document);
        List<VettingRuleEngine.Output> outputs=new ArrayList<>();ObjectNode fields=ledger.putObject("fields");
        for(String field:Arrays.asList("number","title")){
            List<Declaration> own=new ArrayList<>();Set<String> values=new LinkedHashSet<>();for(Declaration d:declarations)if(field.equals(d.field)&&"identity_candidate".equals(d.classification)){own.add(d);values.add(comparison(d.raw));}
            JsonNode baseline=authority==null?JsonUtils.mapper().nullNode():authority.path("core").path("fields").path(field);boolean eligible=baseline.path("eligible").asBoolean()&&baseline.path("value").isTextual()&&!comparison(baseline.path("value").asText()).isEmpty();String expected=eligible?comparison(baseline.path("value").asText()):null;
            boolean baselineDifference=eligible&&values.stream().anyMatch(value->!value.equals(expected)),sourceDifference=values.size()>1;
            ObjectNode assessment=fields.putObject(field);assessment.put("authorityStatus",eligible?"eligible":"unknown");assessment.put("ownDeclarations",0);assessment.put("candidateDeclarations",own.size());assessment.put("scopeStatus","unknown");assessment.put("distinctComparisonValues",values.size());assessment.put("status",own.isEmpty()?"unknown_no_own_declaration":baselineDifference||sourceDifference?"candidate_difference_requires_review":"candidate_agreement_scope_unknown");
            if(baselineDifference||sourceDifference){List<VettingRuleEngine.Evidence> quotes=new ArrayList<>();for(Declaration d:own)quotes.add(d.evidence());
                String title="number".equals(field)?"Contract number candidates require coordination":"Project or contract title candidates require coordination";
                String summary=(sourceDifference?"Explicit labelled identity candidates contain different values. ":"")+(baselineDifference?"A candidate value differs from the adopted "+field+" field. ":"")+"Whether these candidates identify this package remains unknown; the difference requires source-scope review. "+("title".equals(field)?"No adopted alias map proves that different title strings describe the same project. ":"")+"Only Unicode whitespace is collapsed for comparison; original quotations are retained. Observed native stories do not establish physical page use.";
                outputs.add(new VettingRuleEngine.Output("package_identity_"+field+"_coordination",title,summary,"Review the exact declarations and the frozen adopted identity. Confirm intended values or explicitly adopt an alias; no value is corrected automatically.","medium",VettingIssueType.CONFLICT.getGroup(),quotes));
            }
        }
        ledger.put("findingCandidates",outputs.size());ledger.put("legalProblemCountStatus","unknown");return new Review(outputs,ledger);
    }
    private static JsonNode context(DocumentBlock block){JsonNode raw=block.getReadingContext();return raw==null?JsonUtils.mapper().createObjectNode():raw.path(CONTEXT);}
    private static boolean exampleHeading(String before){String normalized=before.trim();if(normalized.isEmpty())return false;int line=normalized.lastIndexOf('\n');return EXAMPLE.matcher(comparison(normalized.substring(line+1))).matches();}
    static String explicitScopeExclusion(String raw){String text=comparison(raw);return EXAMPLE.matcher(text).matches()?"template_or_example":EXTERNAL_HEADING.matcher(text).matches()?"external_reference":null;}
    private static String occurrence(VettingRuleEngine.Source source,DocumentBlock block,int start,int end){JsonNode c=context(block);return VettingCorpus.hash(JsonUtils.write(Arrays.asList(source.getId(),source.getHash(),c.path("originalSourceSha256").asText(),block.getSourcePosition(),block.getId(),c.path("startOffset").asInt()+start,c.path("startOffset").asInt()+end)));}
    /** Does not alter case, punctuation, zeroes, Unicode compatibility characters or stored raw values. */
    static String comparison(String raw){StringBuilder out=new StringBuilder();boolean space=false;if(raw!=null)for(int i=0;i<raw.length();){int cp=raw.codePointAt(i);i+=Character.charCount(cp);if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){space=out.length()>0;}else{if(space)out.append(' ');out.appendCodePoint(cp);space=false;}}return out.toString();}
}
