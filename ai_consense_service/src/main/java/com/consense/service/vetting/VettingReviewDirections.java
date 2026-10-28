package com.consense.service.vetting;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Small neighboring review assignments; no source or answer content is a requirement. */
final class VettingReviewDirections {
    static final class Direction {
        final String id,title,query;
        final List<VettingChecklistCatalog.Item> items;
        Direction(String id,String title,String query,List<VettingChecklistCatalog.Item> items){
            this.id=id;this.title=title;this.query=query;this.items=Collections.unmodifiableList(new ArrayList<>(items));
        }
        List<String> itemIds(){List<String> ids=new ArrayList<>();for(VettingChecklistCatalog.Item item:items)ids.add(item.getId());return ids;}
        List<String> explicitProjectClauses(){
            Set<String> clauses=new LinkedHashSet<>();
            java.util.regex.Pattern locator=java.util.regex.Pattern.compile("(?i)\\b[A-Z][A-Z0-9_-]*\\.[A-Z0-9]+(?:\\.[A-Z0-9]+)*(?:\\([A-Z0-9]+\\))*");
            for(VettingChecklistCatalog.Item item:items){java.util.regex.Matcher match=locator.matcher(item.getClause());while(match.find())clauses.add(match.group().toUpperCase(Locale.ROOT));}
            return new ArrayList<>(clauses);
        }
        String initialQuery(){
            if(items.isEmpty())return query;
            Set<String> anchors=new LinkedHashSet<>();for(VettingChecklistCatalog.Item item:items)anchors.add(item.getClause());
            String subjects=initialSubjects(items.get(0).getId());
            return subjects==null?query:items.get(0).getSection()+"; "+String.join("; ",anchors)+"; "+subjects;
        }
        String instructions(){
            StringBuilder out=new StringBuilder("Assigned review direction: ").append(id).append(". ").append(title)
                    .append("\nAssess only these assigned checklist requirements. Preserve a separate disposition for each assessed row. ")
                    .append("Omit an unassessed row; never infer fulfilment from an empty findings list.\n");
            for(VettingChecklistCatalog.Item item:items){
                out.append(item.getId()).append(" | ").append(item.getSection()).append(" | ")
                        .append(item.getClause()).append(" | ").append(item.getDescription()).append('\n');
                if("mixed_contract_pricing".equals(item.getReviewScope()))out.append("Scope for ").append(item.getId())
                        .append(" (").append(item.getReviewScope()).append("): ").append(item.getScopeNote())
                        .append(" Assess the supported contract-text checks independently. ")
                        .append("Do not withhold a supported contract-text assessment merely because excluded pricing or drawing evidence is unavailable. ")
                        .append("State that the excluded part is not assessed. If an in-scope conclusion itself needs missing evidence, retain insufficient_context and name the missing evidence. ")
                        .append("Do not mark the entire row fulfilled from a partial check.\n");
            }
            if(items.isEmpty())out.append("This is a general review direction; checklistAssessments must be an empty array.\n");
            return out.toString();
        }
    }
    static List<Direction> plan(String[] generalTopics){
        List<Direction> plan=new ArrayList<>();List<VettingChecklistCatalog.Item> batch=new ArrayList<>();
        String section=null;int groups=0,ordinal=0;
        for(VettingChecklistCatalog.LogicalGroup group:VettingChecklistCatalog.logicalGroups()){
            List<VettingChecklistCatalog.Item> eligible=new ArrayList<>();
            for(String id:group.getSourceItemIds())for(VettingChecklistCatalog.Item item:VettingChecklistCatalog.items())
                if(id.equals(item.getId())&&!"pricing_document_excluded".equals(item.getReviewScope()))eligible.add(item);
            if(eligible.isEmpty())continue;
            String next=eligible.get(0).getSection();
            if(!batch.isEmpty()&&(!Objects.equals(section,next)||groups==3)){
                plan.add(checklist(++ordinal,section,batch));batch=new ArrayList<>();groups=0;
            }
            section=next;batch.addAll(eligible);groups++;
        }
        if(!batch.isEmpty())plan.add(checklist(++ordinal,section,batch));
        for(int start=0;start<generalTopics.length;start+=3){
            String query=String.join("; ",Arrays.copyOfRange(generalTopics,start,Math.min(start+3,generalTopics.length)));
            plan.add(new Direction("GENERAL-"+(start/3+1),"General tender review "+(start+1)+"–"+Math.min(start+3,generalTopics.length),query,Collections.emptyList()));
        }
        return plan;
    }
    private static Direction checklist(int ordinal,String section,List<VettingChecklistCatalog.Item> items){
        StringBuilder query=new StringBuilder(section);for(VettingChecklistCatalog.Item item:items)query.append("; ").append(item.getClause()).append(' ').append(item.getDescription());
        return new Direction("CHECKLIST-"+ordinal,section+" checklist review",query.toString(),items);
    }
    private static String initialSubjects(String firstItem){
        switch(firstItem){
            case "APP-I-001":return "GS CEW 2020 specification applicability and parts of the Works";
            case "APP-I-002":return "SL guidance contract type and optional clause selection; project amendments .P suffix; DCMBI and Technical Feedback updates; GCC 1.1 defined terms and capitalization, GCC 8.1 commencement; exclusion of extension of time or cost claims; CM instructions for certain versus optional works and timing or location; appendix clause numbers, headings and page headers";
            case "APP-I-009":return "Major Works description, detailed technical worksections and scope of financial obligations";
            case "APP-I-010":return "Site boundary and possession GCC 1.1; outside Site works and permission; works below high water levels";
            case "APP-I-012":return "Working drawing availability; Type Ma and Type Mi design stages, duplicated or missing design items, performance criteria PRE.B6.085; first and revised submission approval periods PRE.B4.040 PRE.B9.630";
            case "APP-I-015":return "Record plan submission before completion; contractor design timing PRE.B6.085 PRE.B9.630";
            case "APP-I-016":return "Approved specialist works lists; programme submission period GCC 5.7 Letter of Acceptance DCT-F22; management team direct employment, full-time and exempt superintendents";
            case "APP-I-019":return "AQCC SQCC numbers; superintendent safety training; contractor design approval, design intent and Employer requirements, performance criteria, specification references PRE.B6.APPEND5";
            case "APP-I-025":return "Major materials for interim valuation; waterproofing warranty applicability and leakage sensitivity; conditional CM instructed versus certain post-completion care works";
            case "APP-I-028":return "Site portions SCC22.3(11), outside Site excavation permits; contractor design items PRE.B6.085(1)(e)(f), design intent and drawing number references Appendix B";
            case "APP-I-032":return "Abbreviation definitions; TD Technical Director versus Transport Department PRE.B6.060(18)";
            case "APP-I-034":return "Construction plant overhaul lists; BEAM Plus New Buildings edition, credits, prerequisites and appendix references; checking frequency and clause references";
            case "APP-I-039":return "Highways Department versus other maintained areas, excavation permit requirements PRE.B3.060(7) PRE.B6.810(2)(3); Government Entrusted Works and drawing reference; material storage location plan submission time";
            case "APP-I-042":return "Applicable documentation; Direct Testing Contractor tests and matching lists";
            case "APP-I-044":return "Applicable CTA disciplines and job specifications; existing and new hoarding types and extent; safety certificate submission frequency PRE.B10.010";
            case "APP-I-048":return "Publicity boards and accident statistic boards quantities and source references";
            case "APP-I-049":return "Direct Contractors and Direct Testing Contractors special attendance PRE.B12.510 to PRE.B12.590; Utility Undertaking attendance PRE.B12.620 to PRE.B12.640 and matching works lists";
            case "APP-I-052":return "Direct Contractors PRE.B11.010(2), Utility Undertaking PRE.B11.010(1), applicable installations";
            case "APP-I-055":return "Builder's works for building services, attendance scope and central specification PRE.B12.230";
            case "APP-I-056":return "Contract Drawings include Appendix A Site Phase Estate Boundary Plans and Appendix C sketches and details";
            case "APP-I-057":return "Reference-only drawings and documents; GCC 1.1 Drawings; contractual execution requirements in Appendices A B C versus Appendix U";
            default:return null;
        }
    }
    static List<String> searchQueries(String input){
        return searchQueries(input,1200);
    }
    private static List<String> searchQueries(String input,int maximumBytes){
        String text=input.trim().replaceAll("\\s+"," ");List<String> queries=new ArrayList<>();int start=0;
        while(start<text.length()){
            int end=start,bytes=0,lastSpace=-1;
            while(end<text.length()){
                int codepoint=text.codePointAt(end);int size=new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8).length;
                if(bytes+size>maximumBytes)break;
                if(codepoint==' ')lastSpace=end;
                bytes+=size;end+=Character.charCount(codepoint);
            }
            if(end<text.length()&&lastSpace>start)end=lastSpace;
            queries.add(text.substring(start,end));start=end;
            while(start<text.length()&&text.charAt(start)==' ')start++;
        }
        return queries;
    }
    static <T> List<T> searchWithFallback(String query,java.util.function.Function<String,List<T>> search){
        try{return search.apply(query);}
        catch(RuntimeException rejected){
            if(!OerCanonicalRetrievalClient.queryBudgetRejected(rejected))throw rejected;
            List<String> parts=searchQueries(query,400);if(parts.size()<2)throw rejected;
            List<T> result=new ArrayList<>();for(String part:parts)result.addAll(search.apply(part));return result;
        }
    }
}
