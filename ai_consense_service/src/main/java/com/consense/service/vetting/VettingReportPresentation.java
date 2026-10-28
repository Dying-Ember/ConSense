package com.consense.service.vetting;

import com.consense.common.LocalizedText;
import com.consense.web.dto.VettingDtos.*;
import com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO;
import com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO;
import java.util.*;

/** One business table model for Word, PDF and the online report; audit data stays separate. */
public final class VettingReportPresentation {
    private VettingReportPresentation() { }
    public static final class Table {
        private final String title;
        private final List<String> headings;
        private final int[] widths;
        private final List<List<String>> rows = new ArrayList<>();
        private final List<String> findingCodes = new ArrayList<>();
        Table(String title, List<String> headings, int... widths) { this.title=title;this.headings=headings;this.widths=widths; }
        public String getTitle() { return title; }
        public List<String> getHeadings() { return Collections.unmodifiableList(headings); }
        public int[] getWidths() { return widths.clone(); }
        public List<List<String>> getRows() { return Collections.unmodifiableList(rows); }
        public List<String> getFindingCodes() { return Collections.unmodifiableList(findingCodes); }
        void row(String... values) { rows.add(Collections.unmodifiableList(Arrays.asList(values))); }
        // Rendering only: keep the existing five-column JSON shape and full row values.
        boolean commentsHeader() { return headings.size()==5 && "Comments".equals(headings.get(3)); }
        String sectionNumber() { int at=title.indexOf(". ");return at>0&&title.substring(0,at).matches("\\d+")?title.substring(0,at+1):""; }
        String sectionLabel() { String number=sectionNumber();return number.isEmpty()?title:title.substring(number.length()).trim(); }
    }
    @lombok.Getter @lombok.AllArgsConstructor
    public static final class SectionSummary {
        private final String documentId,fileKey,fileName,parseStatus,reviewState,summary;
        private final int findingCount,totalSegments,reviewedSegments,totalChars,reviewedChars;
        private final List<String> warnings;
    }
    public static List<SectionSummary> sectionSummaries(List<FindingVO> findings,CoverageVO coverage) {
        List<SectionSummary> summaries=new ArrayList<>();
        if(coverage==null)return summaries;
        for(DocumentCoverageVO document:safe(coverage.getDocuments()))if(document!=null) {
            int count=0,unclassified=0;Set<String> containers=new LinkedHashSet<>();
            for(FindingVO finding:safe(findings))if(finding!=null) {
                FindingEvidence source=primary(finding);
                String sourceId=source==null?"":str(source.getDocumentId());
                String key=nonblank(source==null?null:source.getFileKey(),finding.getFileKey());
                if(!sourceId.isEmpty()?sourceId.equals(document.getDocumentId()):!key.isEmpty()&&key.equals(document.getFileKey())
                        &&safe(coverage.getDocuments()).stream().filter(d->d!=null&&key.equals(d.getFileKey())).count()==1) {
                    count++;if(finding.getSourceCandidateContainer()==null)unclassified++;else containers.add(finding.getSourceCandidateContainer().getId());
                }
            }
            String state="PLACEHOLDER".equalsIgnoreCase(document.getParseStatus())?"numbered_placeholder"
                    :!"PARSED".equals(document.getParseStatus())||document.getTotalSegments()<=0||document.getTextChars()<=0?"insufficient_evidence"
                    :document.getReviewedSegments()<=0&&document.getReviewedChars()<=0?"not_covered"
                    :document.getReviewedSegments()<document.getTotalSegments()||document.getReviewedChars()<document.getTextChars()?"not_completed"
                    :"full_countercheck_unproven";
            String summary="Document ID: "+nonblank(document.getDocumentId(),"Not recorded")+". Parse status: "+nonblank(document.getParseStatus(),"Not recorded")
                    +". Raw candidate records: "+count+". Recorded exact-source candidate containers: "+containers.size()+"; unclassified candidate records: "+unclassified+". Legal problem count: undetermined. Review state: "+state+". Recorded segments: "+document.getReviewedSegments()+"/"+document.getTotalSegments()
                    +"; characters: "+document.getReviewedChars()+"/"+document.getTextChars()+". Complete source and substantive countercheck have not been established.";
            if("numbered_placeholder".equals(state))summary+=" Numbered placeholder is recorded; substantive content is not recorded as expanded.";
            if(!safe(document.getWarnings()).isEmpty())summary+=" Recorded limitations: "+document.getWarnings().stream().map(value->VettingReportWriter.warning(value,"en")).collect(java.util.stream.Collectors.joining("; "));
            summaries.add(new SectionSummary(document.getDocumentId(),document.getFileKey(),document.getFileName(),document.getParseStatus(),state,summary,
                    count,document.getTotalSegments(),document.getReviewedSegments(),document.getTextChars(),document.getReviewedChars(),safe(document.getWarnings())));
        }
        return summaries;
    }
    public static List<Table> comments(List<FindingVO> findings, CoverageVO coverage) {
        return commentTables(findings,coverage,false);
    }
    static List<Table> readerComments(List<FindingVO> findings,CoverageVO coverage) {
        return commentTables(findings,coverage,true);
    }
    private static List<Table> commentTables(List<FindingVO> findings,CoverageVO coverage,boolean reader) {
        Map<String,Group> groups=new LinkedHashMap<>();
        if(findings!=null) for(FindingVO f:findings) if(f!=null) {
            FindingEvidence source=primary(f);
            String key=nonblank(source==null?null:source.getFileKey(),f.getFileKey());
            String filename=source==null?"":str(source.getFileName());
            String id=source==null?"":str(source.getDocumentId());
            if(coverage!=null&&(filename.isEmpty()||id.isEmpty())) {
                DocumentCoverageVO selected=null;
                for(DocumentCoverageVO d:safe(coverage.getDocuments())) if(d!=null&&(!id.isEmpty()?id.equals(d.getDocumentId()):key.equals(d.getFileKey()))) {
                    if(selected!=null) { selected=null;break; } selected=d;
                }
                if(selected!=null) { filename=nonblank(filename,selected.getFileName());id=nonblank(id,selected.getDocumentId()); }
            }
            String identity=id.isEmpty()?key+"|"+filename:"id:"+id;
            Group group=groups.get(identity);
            if(group==null) { group=new Group(key,filename);groups.put(identity,group); }
            group.findings.add(f);
        }
        for(SectionSummary summary:sectionSummaries(findings,coverage)) {
            String identity=str(summary.getDocumentId()).isEmpty()?str(summary.getFileKey())+"|"+str(summary.getFileName()):"id:"+summary.getDocumentId();
            Group group=groups.get(identity);
            if(group==null){group=new Group(str(summary.getFileKey()),str(summary.getFileName()));groups.put(identity,group);}
            group.summary=summary;
        }
        List<Group> ordered=new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((Group g)->rank(g.key,g.filename)).thenComparing(g->g.filename));
        List<Table> tables=new ArrayList<>();int[] sectionCounts=new int[9];
        int[] widths=reader?new int[]{7,10,15,43,25}:new int[]{7,11,15,52,15};
        for(Group g:ordered) {
            int section=rank(g.key,g.filename);
            Table table=new Table(section+". "+label(g.key,g.filename),Arrays.asList("", "Page", "Clause / Item", "Comments", "Remarks by Project Team (if any)"),widths);
            Map<String,String> readerGroupLeaders=new HashMap<>();
            if(reader)for(FindingVO finding:g.findings)if(finding.getSourceCandidateContainer()!=null&&!str(finding.getSourceCandidateContainer().getId()).isEmpty()&&!str(finding.getCode()).isEmpty())
                readerGroupLeaders.putIfAbsent(finding.getSourceCandidateContainer().getId(),finding.getCode());
            for(FindingVO f:g.findings) {
                FindingEvidence source=primary(f);
                String page=nonblank(source==null?null:source.getPageNo(),f.getPageNo());
                String clause=nonblank(f.getRefs(),f.getLocation());
                String comment=english(f.getBody());
                if(comment.isEmpty())comment=english(f.getTitle());
                if(!english(f.getImpact()).isEmpty())comment+="\nReason and impact: "+english(f.getImpact());
                if(!str(f.getExpected()).isEmpty())comment+="\nExpected basis: "+f.getExpected();
                if(!english(f.getSuggestion()).isEmpty())comment+="\nSuggested action: "+english(f.getSuggestion());
                BusinessDetailsVO business=f.getBusinessDetails();
                String facts=business==null?"Not recorded; review required":nonblank(business.getFactBasis(),"Not recorded; review required");
                String pending=business==null?"Not recorded":nonblank(String.join("; ",safe(business.getPendingFacts())),"Not recorded");
                comment="Project fact basis: "+facts+"\nFacts pending confirmation: "+pending+"\n"+comment;
                if(business!=null)comment+="\nIssue class: "+nonblank(f.getIssueTypeCode(),"Not recorded")
                        +"\nSub-dimension: "+nonblank(business.getSubDimension(),"Not recorded")
                        +"\nAction: "+nonblank(business.getSuggestedAction(),"Not recorded; review required")
                        +"\nChecklist connection: "+checklistLinks(business.getChecklistItemIds());
                if(f.getSourceCandidateContainer()!=null) {
                    int members=f.getSourceCandidateContainer().getRawMemberCount();
                    comment=(members>1?"These "+members+" original candidates cite the same source locations and quoted text and require separate review; issue equivalence is unconfirmed."
                            :"This original candidate records source locations and quoted text and still requires review.")
                            +" Legal problem count: undetermined. Original candidate: "+f.getCode()+".\n"+comment;
                }
                if(reader){comment=readerComment(f,f.getSourceCandidateContainer()==null?null:readerGroupLeaders.get(f.getSourceCandidateContainer().getId()));clause=readerFindingLocation(f);page=readerFindingPages(f);}
                String remarks=str(f.getReviewRemarks());
                if(!str(f.getActionTaken()).isEmpty())remarks+=(remarks.isEmpty()?"":"\n")+"Action taken: "+f.getActionTaken();
                if(f.getAddendumRequired()!=null)remarks+=(remarks.isEmpty()?"":"\n")+"Include in tender addendum: "+(f.getAddendumRequired()?"Required":"Not required");
                if(reader){remarks="Review status: "+readerStatus(f.getStatus())+(remarks.isEmpty()?"":"\n"+remarks);if(f.getReviewUpdatedAt()!=null)remarks+="\nReview saved: "+f.getReviewUpdatedAt();}
                table.row(section+"."+(++sectionCounts[section]),page.isEmpty()?"Not located":page,clause.isEmpty()?"Not located":displayLocation(clause),comment,remarks);
                table.findingCodes.add(str(f.getCode()));
            }
            if(g.summary!=null)table.row(section+".S","-","Review summary",reader?readerSummary(g.summary):g.summary.getSummary(),"");
            tables.add(table);
        }
        if(tables.isEmpty()) {
            Table t=new Table("Comments recorded",Arrays.asList("", "Page", "Clause / Item", "Comments", "Remarks by Project Team (if any)"),widths);
            t.row("-","-","-","No findings were recorded by this task. This does not establish that the documents have no issues. See the review scope and limitations in Appendix II.","");tables.add(t);
        }
        return tables;
    }
    private static String readerStatus(String value) {
        if("OPEN".equalsIgnoreCase(value))return "Awaiting project-team review";
        if("HANDLED".equalsIgnoreCase(value))return "Handled";
        if("ASSIGNED".equalsIgnoreCase(value))return "Assigned";
        return "Status unconfirmed";
    }
    static String readerLocation(String value) {
        String raw=str(value);if(raw.isEmpty())return "Source position unconfirmed";
        String displayed=displayLocation(raw);
        if(!displayed.equals(raw))return displayed+" (formal clause ownership unconfirmed)";
        if(raw.contains("body/")||raw.contains("word/")||raw.contains("xmlns"))return "Recorded source position; formal clause location unconfirmed";
        return "Recorded source location: "+raw;
    }
    private static String recordedClause(FindingEvidence evidence) {
        if(evidence==null)return "";
        String clause=str(evidence.getClauseId());
        if(!clause.isEmpty())return clause;
        com.fasterxml.jackson.databind.JsonNode adoption=evidence.getCanonicalEvidence()==null?null:evidence.getCanonicalEvidence().getAdoption();
        return adoption!=null&&"explicit_source_reviewed_selection".equals(adoption.path("reviewStatus").asText())?str(adoption.path("clause").path("clauseId").asText()):"";
    }
    static String readerEvidenceLocation(FindingEvidence evidence) {
        if(evidence==null)return readerLocation(null);
        String clause=recordedClause(evidence),location=readerLocation(evidence.getAnchor());
        if(clause.isEmpty())return location;
        location=location.replace(" (formal clause ownership unconfirmed)","").replace("; formal clause location unconfirmed"," (exact position unconfirmed)");
        return "Recorded source clause: "+clause+(str(evidence.getAnchor()).isEmpty()?"":"; "+location);
    }
    static String evidenceName(FindingEvidence evidence) {
        if(evidence==null)return "Source file unconfirmed";
        if(!str(evidence.getFileName()).isEmpty())return evidence.getFileName();
        com.fasterxml.jackson.databind.JsonNode fixed=evidence.getFixedBaselineEvidence();
        if(fixed!=null&&fixed.isObject()){
            String edition=fixed.path("editionId").asText();
            switch(edition){
                case "sl2022-original":return "SL2022 Original";
                case "sl2022-batch01":return "SL2022 Batch 1";
                case "sl2022-batch02-rev1":return "SL2022 Batch 2 revision 1";
                case "sl2022-batch03":return "SL2022 Batch 3";
                case "sl2022-batch04":return "SL2022 Batch 4";
                case "sl2026-original":return "SL2026 Original";
                default:
                    String family=fixed.path("sourceFamily").asText();
                    if("SL".equals(family))return "SL reference (edition unconfirmed)";
                    if("GCC".equals(family)||"GCT".equals(family))return family+" reference";
            }
        }
        return "Source file unconfirmed";
    }
    static String findingLocation(List<FindingEvidence> evidence){
        List<String> locations=new ArrayList<>();
        for(FindingEvidence item:safe(evidence))if(item!=null){
            String position=nonblank(item.getPageNo(),item.getAnchor());
            if(item.getFixedBaselineEvidence()!=null&&position.matches("(?i)P?\\d+"))position="page "+position.replaceFirst("(?i)^P","");
            locations.add(evidenceName(item)+(str(position).isEmpty()?"":" · "+position));
        }
        return String.join("; ",locations);
    }
    private static String readerFindingLocation(FindingVO finding) {
        LinkedHashSet<String> files=new LinkedHashSet<>(),positions=new LinkedHashSet<>();
        for(FindingEvidence evidence:safe(finding.getEvidence()))if(evidence!=null&&"source".equalsIgnoreCase(evidence.getSide()))files.add(str(evidence.getDocumentId())+"|"+str(evidence.getFileName()));
        for(FindingEvidence evidence:safe(finding.getEvidence()))if(evidence!=null&&"source".equalsIgnoreCase(evidence.getSide()))positions.add((files.size()>1?nonblank(evidence.getFileName(),"Source file unconfirmed")+": ":"")+readerEvidenceLocation(evidence));
        if(positions.isEmpty())positions.add(primary(finding)==null?"Recorded candidate reference (formal clause ownership unconfirmed): "+readerLocation(nonblank(finding.getRefs(),finding.getLocation())):readerEvidenceLocation(primary(finding)));
        return String.join("\n",positions);
    }
    private static String readerFindingPages(FindingVO finding) {
        LinkedHashSet<String> pages=new LinkedHashSet<>();
        for(FindingEvidence evidence:safe(finding.getEvidence()))if(evidence!=null&&"source".equalsIgnoreCase(evidence.getSide())&&!str(evidence.getPageNo()).isEmpty())pages.add(evidence.getPageNo());
        return pages.isEmpty()?nonblank(primary(finding)==null?finding.getPageNo():primary(finding).getPageNo(),"Not located"):String.join(" / ",pages);
    }
    static String readerNarrative(String value,FindingVO finding) {
        String text=value==null?"":value;
        for(FindingEvidence source:safe(finding.getEvidence()))if(source!=null) {
            String position=readerEvidenceLocation(source);
            if(source.getCanonicalEvidence()!=null&&!str(source.getCanonicalEvidence().getCanonicalChunkId()).isEmpty())
                text=text.replaceAll("(?<![A-Za-z0-9_:-])"+java.util.regex.Pattern.quote(source.getCanonicalEvidence().getCanonicalChunkId())+"(?![A-Za-z0-9_:-])",java.util.regex.Matcher.quoteReplacement(position));
            if(!str(source.getAnchor()).isEmpty()&&source.getAnchor().startsWith("body/"))text=text.replaceAll("(?<![A-Za-z0-9_:/-])"+java.util.regex.Pattern.quote(source.getAnchor())+"(?![A-Za-z0-9_:/-])",java.util.regex.Matcher.quoteReplacement(position));
            if(str(source.getAnchor()).matches("body/\\d+/paragraph")) {
                String shortAnchor=source.getAnchor().substring(0,source.getAnchor().length()-"/paragraph".length());
                text=text.replaceAll("(?<![A-Za-z0-9_:/-])"+java.util.regex.Pattern.quote(shortAnchor)+"(?![A-Za-z0-9_:/-])",java.util.regex.Matcher.quoteReplacement(position));
            }
        }
        return text.replaceAll("(?<![A-Za-z0-9_:/-])body/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*(?![A-Za-z0-9_/-])","Original source location unverified");
    }
    static String readerComment(FindingVO finding) {
        return readerComment(finding,str(finding.getCode()));
    }
    private static String readerComment(FindingVO finding,String groupLeader) {
        String provenance="model".equalsIgnoreCase(finding.getSource())?"Original model candidate":"Saved review candidate";
        boolean grouped=finding.getSourceCandidateContainer()!=null&&!str(groupLeader).isEmpty();
        boolean first=!grouped||groupLeader.equals(finding.getCode());
        StringBuilder text=new StringBuilder(first?"Review suggestion (not confirmed). "+provenance+"; ":"Candidate "+finding.getCode()+"; same-source group qualifications: see candidate "+groupLeader+". ");
        text.append(readerClauseQualification(finding)).append(". ");
        if(grouped&&first)text.append("Same-source group note at candidate ").append(finding.getCode()).append(": equivalence between candidates is unconfirmed; independent legal problem count is undetermined. ");
        LinkedHashSet<String> shown=new LinkedHashSet<>();
        readerParagraph(text,shown,"",readerText(finding.getTitle()),finding);
        readerParagraph(text,shown,"",readerText(finding.getBody()),finding);
        BusinessDetailsVO business=finding.getBusinessDetails();
        if(business!=null)readerParagraph(text,shown,"Basis recorded for review: ",business.getFactBasis(),finding);
        readerParagraph(text,shown,"Reason and impact: ",readerText(finding.getImpact()),finding);
        readerParagraph(text,shown,"Expected basis: ",finding.getExpected(),finding);
        readerParagraph(text,shown,"Suggested action: ",readerText(finding.getSuggestion()),finding);
        if(business!=null&&english(finding.getSuggestion()).isEmpty())readerParagraph(text,shown,"Suggested action: ",readerAction(business.getSuggestedAction()),finding);
        if(business!=null)readerParagraph(text,shown,"Confirmation required: ",String.join("; ",safe(business.getPendingFacts())),finding);
        if(business!=null)for(String itemId:safe(business.getChecklistItemIds()))for(VettingChecklistCatalog.Item item:VettingChecklistCatalog.items())if(item.getId().equals(itemId))
            readerParagraph(text,shown,"Related Appendix I countercheck: ",item.getClause()+" (row "+item.getId()+")",finding);
        if(!str(finding.getSeverity()).isEmpty())text.append("\nReview priority: ").append(finding.getSeverity());
        return text.toString();
    }
    private static String readerAction(String value) {
        if("delete".equals(value))return "Consider deleting the identified wording, subject to source and project-team confirmation.";
        if("amend".equals(value))return "Consider amending the identified wording, subject to source and project-team confirmation.";
        if("insert".equals(value))return "Consider inserting the required wording, subject to source and project-team confirmation.";
        if("review".equals(value)||"seek_clarification".equals(value))return "Seek clarification and review the source before deciding any amendment.";
        return str(value).replace('_',' ');
    }
    static String readerClauseQualification(FindingVO finding) {
        boolean source=false;
        for(FindingEvidence evidence:safe(finding.getEvidence()))if(evidence!=null&&"source".equalsIgnoreCase(evidence.getSide())){source=true;if(recordedClause(evidence).isEmpty())return "formal clause numbers stated below are unconfirmed";}
        return source||!recordedClause(primary(finding)).isEmpty()?"clause attribution beyond the recorded source clause is unconfirmed":"formal clause numbers stated below are unconfirmed";
    }
    private static void readerParagraph(StringBuilder out,Set<String> shown,String label,String value,FindingVO finding) {
        String raw=value==null?"":value;
        if(!raw.trim().isEmpty()&&shown.add(raw)){if(shown.size()>1)out.append('\n');out.append(label).append(readerNarrative(raw,finding));}
    }
    private static String readerText(LocalizedText value) { return value==null?"":value.pick("en"); }
    static String readerSummary(SectionSummary summary) {
        String state=summary.getReviewState();
        String limitation="numbered_placeholder".equals(state)?"Only a numbered placeholder is recorded; substantive content is unconfirmed."
                :"not_covered".equals(state)?"No substantive countercheck is recorded."
                :"not_completed".equals(state)?"The source review is incomplete."
                :"insufficient_evidence".equals(state)?"Available source evidence is insufficient."
                :"Full source and substantive countercheck have not been established.";
        return summary.getFindingCount()+" candidate comments recorded; this is not a confirmed count of independent legal problems. "+limitation;
    }
    static Table readerEvidenceIndex(List<FindingVO> findings,CoverageVO coverage) {
        Table index=new Table("Comment and source quotation index",Arrays.asList("Comment","Evidence record","Document / Clause"),12,28,60);
        for(Table table:readerComments(findings,coverage))for(int i=0;i<table.findingCodes.size();i++)index.row(table.rows.get(i).get(0),table.findingCodes.get(i),table.title+" / "+table.rows.get(i).get(2));
        return index;
    }
    static String displayLocation(String value) {
        if(!value.matches("body/\\d+/paragraph(?: / body/\\d+/paragraph)*"))return value;
        return "Body paragraph "+value.replaceAll("body/(\\d+)/paragraph","$1");
    }
    static String checklistLinks(List<String> itemIds) {
        List<String> links=new ArrayList<>();
        for(String id:safe(itemIds)) {
            VettingChecklistCatalog.LogicalGroup group=VettingChecklistCatalog.logicalGroups().stream()
                    .filter(g->g.getSourceItemIds().contains(id)).findFirst().orElse(null);
            links.add(group!=null?group.getId()+" / "+id:VettingChecklistCatalog.annexItemIds().contains(id)
                    ?id+" (supplementary annex)":id+" (logical link not recorded)");
        }
        return links.isEmpty()?"Not recorded":String.join("; ",links);
    }
    public static Table evidenceIndex(List<FindingVO> findings,CoverageVO coverage) {
        Table index=new Table("Comment and evidence index",Arrays.asList("Comment","Evidence record","Document / Clause"),12,28,60);
        for(Table table:comments(findings,coverage))for(int i=0;i<table.findingCodes.size();i++)
            index.row(table.rows.get(i).get(0),table.findingCodes.get(i),table.title+" / "+table.rows.get(i).get(2));
        return index;
    }
    public static List<Table> checklist(List<VettingChecklistCatalog.Result> results) {
        return checklist(results,Collections.emptyList());
    }
    public static List<Table> checklist(List<VettingChecklistCatalog.Result> results,List<AutomaticGroupVO> automatic) {
        return checklistTables(results,automatic,false);
    }
    static List<Table> readerChecklist(List<VettingChecklistCatalog.Result> results,List<AutomaticGroupVO> automatic) {
        return checklistTables(results,automatic,true);
    }
    private static List<Table> checklistTables(List<VettingChecklistCatalog.Result> results,List<AutomaticGroupVO> automatic,boolean reader) {
        Map<String,VettingChecklistCatalog.Result> manual=new HashMap<>();
        for(VettingChecklistCatalog.Result r:safe(results))if(r!=null)manual.put(r.getId(),r);
        Map<String,AutomaticItemVO> suggestions=new HashMap<>();
        for(AutomaticGroupVO group:safe(automatic))for(AutomaticItemVO item:safe(group.getSubItemAssessments()))suggestions.put(item.getItemId(),item);
        List<Table> tables=new ArrayList<>();String section=null;Table table=null;
        for(VettingChecklistCatalog.Item item:VettingChecklistCatalog.items()) {
            if(!item.getSection().equals(section)) {
                section=item.getSection();
                table=item.getTemplatePage()==14
                        ?new Table(section,Arrays.asList("No.","Common examples for different types of builder's works for building services installations specified in building tenders","Suggested actions to be taken by the Contract Team"),6,44,50)
                        :new Table(section,Arrays.asList(item.getSection().startsWith("BILL NO.")?"Bills of Quantities":"PSS Clause", "Description", "Remarks by Contract Team"),28,52,20);
                tables.add(table);
            }
            VettingChecklistCatalog.Result r=manual.get(item.getId());
            String status=r==null?"unchecked":str(r.getStatus());
            String remarks="fulfilled".equals(status)?"Checked and fulfilled \u2713":"not_fulfilled".equals(status)?"Checked and not fulfilled \u2717"
                    :"not_applicable".equals(status)?"Not applicable":"Not checked (unknown)";
            String scopeNote=reportScopeNote(item);
            if(!scopeNote.isEmpty())remarks+="\n"+scopeNote;
            if(r!=null&&!str(r.getRemarks()).isEmpty())remarks+="\n"+r.getRemarks();
            AutomaticItemVO suggestion=suggestions.get(item.getId());
            if(suggestion!=null) {
                String mark="fulfilled".equals(suggestion.getStatus())?" \u2713":"not_fulfilled".equals(suggestion.getStatus())?" \u2717":"";
                String reason=reader&&item.getScopeNote().equals(suggestion.getReason())?"":suggestion.getReason();
                String automaticState=reader&&"out_of_scope".equals(suggestion.getStatus())?"Outside automated review scope; no automatic countercheck performed":automaticState(suggestion.getStatus());
                remarks="Human countercheck: "+remarks+"\nAutomatic suggestion: "+automaticState+mark+(reader&&reason!=null&&reason.isEmpty()?"":"\n"+reason);
            }
            if(item.getTemplatePage()==14) {
                String[] parts=item.getDescription().split(" Suggested actions: ",2);
                table.row(item.getClause(),parts[0],(parts.length>1?parts[1]+"\n":"")+"Contract team confirmation: "+remarks);
            } else table.row(item.getClause(),item.getDescription(),remarks);
        }
        return tables;
    }
    private static String automaticState(String status) {
        if("fulfilled".equals(status))return "Fulfilled";
        if("not_fulfilled".equals(status))return "Not fulfilled";
        if("not_applicable".equals(status))return "Not applicable";
        if("insufficient_evidence".equals(status))return "Evidence incomplete";
        if("not_assessed".equals(status))return "Not assessed";
        return "Unknown status: "+str(status);
    }
    /** Full scope guidance is in the report introduction/API; compact row notes preserve the sample layout. */
    private static String reportScopeNote(VettingChecklistCatalog.Item item) {
        if("pricing_document_excluded".equals(item.getReviewScope()))return "Outside scope: pricing-document countercheck.";
        if("mixed_contract_pricing".equals(item.getReviewScope()))return "PRE / contract wording only. Pricing excluded.";
        return "";
    }
    private static FindingEvidence primary(FindingVO f) {
        for(FindingEvidence e:safe(f.getEvidence()))if(e!=null&&"source".equalsIgnoreCase(e.getSide()))return e;
        for(FindingEvidence e:safe(f.getEvidence()))if(e!=null)return e;
        return null;
    }
    private static int rank(String key,String filename) {
        String k=key.toUpperCase(Locale.ROOT),n=filename.toLowerCase(Locale.ROOT);
        if(k.equals("NTT")||n.contains("ntt"))return 1;
        if(k.equals("GCT")||n.contains("gct"))return 2;
        if(k.equals("SCT")||n.contains("sct"))return 3;
        if(k.equals("FT")||k.equals("FOT")||n.contains("fot"))return 4;
        if(k.equals("AA")||k.equals("AOA")||n.contains("aoa"))return 5;
        if(k.equals("SCC")||k.equals("GCC")||k.equals("COC")||n.contains("scc")||n.contains("coc"))return 6;
        if(k.equals("PSS")||k.equals("PRE")||k.equals("SPEC")||n.contains("specification")||n.contains("pre ("))return 7;
        return 8;
    }
    private static String label(String key,String filename) {
        String[] names={"", "Notes to Tenderers", "General Conditions of Tender", "Special Conditions of Tender", "Form of Tender", "Articles of Agreement", "Conditions of Contract", "Project Specific Specification", "Other tender documents"};
        String title=names[rank(key,filename)];return title+(filename.isEmpty()?" ["+key+"]":" - "+filename);
    }
    private static String english(LocalizedText value) { return value==null?"":str(value.pick("en")); }
    private static String nonblank(String a,String b) { return str(a).isEmpty()?str(b):str(a); }
    private static String str(String s) { return s==null?"":s.trim(); }
    private static <T> List<T> safe(List<T> list) { return list==null?Collections.emptyList():list; }
    private static final class Group { final String key,filename;final List<FindingVO> findings=new ArrayList<>();SectionSummary summary;Group(String key,String filename){this.key=key;this.filename=filename;} }
}
