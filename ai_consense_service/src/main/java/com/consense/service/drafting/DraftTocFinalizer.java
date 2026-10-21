package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.domain.DraftPdfArtifact;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.*;
import org.apache.pdfbox.text.*;

/** Final saved Word and PDF share exact bytes; an unsuccessful refresh retains the compiled draft. */
final class DraftTocFinalizer {
    static final String POLICY="native-toc-cache-two-pass-v1";
    private DraftTocFinalizer() { }

    static Result unchanged(byte[] compiled,String reason) {
        Map<String,Object> proof=new LinkedHashMap<>();proof.put("kind","toc_finalization");proof.put("policy",POLICY);
        proof.put("status","UNKNOWN");proof.put("reason",reason);proof.put("compiledDocxSha256",DraftPdfConverter.sha256(compiled));
        return new Result(compiled,compiled,null,null,proof);
    }
    static Result finish(byte[] compiled,Function<byte[],DraftPdfConversionResult> render) {return finish(compiled,null,render);}
    static Result finish(byte[] compiled,String originalSourceSha256,Function<byte[],DraftPdfConversionResult> render) {return finish(compiled,originalSourceSha256,Collections.emptyList(),Collections.emptyList(),render);}
    static Result finish(byte[] compiled,String originalSourceSha256,List<Map<String,Object>> bindings,List<Object> ledger,Function<byte[],DraftPdfConversionResult> render) {
        byte[] frozen=compiled.clone();Result fallback=unchanged(frozen,"TOC_NOT_FINALIZED");
        Map<String,Object> proof=new LinkedHashMap<>(fallback.proof);DraftPdfConversionResult first=null;int conversions=0;proof.put("nativeConversionsAttempted",0);if(originalSourceSha256!=null){proof.put("policy","native-heading-flow-then-toc-bounded-v2");proof.put("headingFlowStatus","PENDING");}
        try {
            DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex initial=editor.inspect(frozen);List<TocPageReference> references=editor.tocPageReferences(frozen);
            if(references.isEmpty()){if(originalSourceSha256!=null)throw new IllegalStateException("HEADING_FLOW_TOC_REQUIRED");proof.put("status","NOT_APPLICABLE");proof.remove("reason");return new Result(frozen,frozen,null,null,proof);}
            proof.put("nativeConversionsAttempted",++conversions);DraftPdfConversionResult renderedFirst=render.apply(frozen);requirePair(frozen,renderedFirst);first=renderedFirst;proof.put("firstPdfSha256",first.getPdfSha256());
            Observation original=observe(first.getPdfBytes(),initial,references);proof.put("firstPoints",original.points);proof.put("firstActualTocPages",original.tocPages);proof.put("renderProfileHash",first.getRenderProfileHash());
            byte[] measuredDocx=frozen;TemplateIndex measuredIndex=initial;Observation before=original;List<NativeHeadingFlow> flows=Collections.emptyList();
            if(originalSourceSha256!=null){proof.put("headingFlowSourceSha256",originalSourceSha256);flows=DraftNativeLayout.activeHeadingFlows(originalSourceSha256,"libreoffice",initial,bindings,ledger,proof);editor.updateNativeHeadingBoundaries(frozen,initial.getSourceSha256(),flows,Collections.emptySet());List<Map<String,Object>> initialPairs=headingPairs(flows,original);proof.put("initialHeadingPairs",initialPairs);Set<String> split=splitHeadings(initialPairs);proof.put("headingFlowRepairCount",split.size());proof.put("measuredSplitHeadings",split);Set<String> roots=DocxTemplateEditor.nativeHeadingRepairRoots(flows,split);proof.put("headingFlowRepairRoots",roots);proof.put("headingFlowRepairRootCount",roots.size());
                if(!split.isEmpty()){DocxEditResult repaired=editor.updateNativeHeadingBoundaries(frozen,initial.getSourceSha256(),flows,split);measuredDocx=repaired.getDocxBytes();measuredIndex=repaired.getIndex();proof.put("repairDocxSha256",repaired.getDocxSha256());proof.put("nativeConversionsAttempted",++conversions);DraftPdfConversionResult measured=render.apply(measuredDocx);requirePair(measuredDocx,measured);proof.put("secondDocxSha256",measured.getDocxSha256());proof.put("secondPdfSha256",measured.getPdfSha256());if(!first.getRenderProfileHash().equals(measured.getRenderProfileHash()))throw new IllegalStateException("TOC_RENDER_PROFILE_CHANGED");before=observe(measured.getPdfBytes(),measuredIndex,editor.tocPageReferences(measuredDocx));editor.updateNativeHeadingBoundaries(measuredDocx,repaired.getDocxSha256(),flows,Collections.emptySet());List<Map<String,Object>> measuredPairs=headingPairs(flows,before);proof.put("measuredHeadingPairs",measuredPairs);proof.put("measurementPoints",before.points);if(!splitHeadings(measuredPairs).isEmpty())throw new IllegalStateException("HEADING_FLOW_STILL_SPLIT_AFTER_REPAIR");}
                else proof.put("measuredHeadingPairs",initialPairs);
            }else proof.put("headingFlowStatus","NOT_REQUESTED");
            proof.put("measurementPass",conversions);proof.put("measurementPoints",before.points);proof.put("measurementActualTocPages",before.tocPages);
            Map<String,String> measured=before.pages;List<Map<String,Object>> recipe=new ArrayList<>();int changed=0;
            List<TocPageReference> measuredReferences=editor.tocPageReferences(measuredDocx);
            for(TocPageReference reference:measuredReferences){String page=measured.get(reference.getTargetBookmark());Map<String,Object> row=new LinkedHashMap<>();row.put("targetBookmark",reference.getTargetBookmark());row.put("fieldStartId",reference.getFieldStartId());row.put("cacheNodeId",reference.getCacheNodeId());row.put("before",reference.getCachedPage());row.put("after",page);recipe.add(row);if(!reference.getCachedPage().equals(page))changed++;}
            proof.put("targetDenominator",measuredReferences.size());proof.put("changedCaches",changed);proof.put("unchangedControls",measuredReferences.size()-changed);proof.put("recipe",recipe);
            DocxEditResult updated=editor.updateTocPageCaches(measuredDocx,measuredIndex.getSourceSha256(),measured);byte[] candidate=updated.getDocxBytes();proof.put("nativeConversionsAttempted",++conversions);if(conversions>3)throw new IllegalStateException("TOC_NATIVE_CONVERSION_BUDGET_EXCEEDED");DraftPdfConversionResult last=render.apply(candidate);requirePair(candidate,last);
            proof.put(conversions==2?"secondDocxSha256":"thirdDocxSha256",last.getDocxSha256());proof.put(conversions==2?"secondPdfSha256":"thirdPdfSha256",last.getPdfSha256());if(!first.getRenderProfileHash().equals(last.getRenderProfileHash()))throw new IllegalStateException("TOC_RENDER_PROFILE_CHANGED");
            List<TocPageReference> finalReferences=editor.tocPageReferences(candidate);Observation after=observe(last.getPdfBytes(),updated.getIndex(),finalReferences);if(!before.points.equals(after.points)||!before.pages.equals(after.pages))throw new IllegalStateException("TOC_TARGET_OR_BINDING_MOVED");
            for(TocPageReference reference:finalReferences)if(!reference.getCachedPage().equals(after.pages.get(reference.getTargetBookmark()))||!reference.getCachedPage().equals(after.tocPages.get(reference.getTargetBookmark())))throw new IllegalStateException("TOC_SECOND_PASS_STILL_STALE");
            if(!flows.isEmpty()){editor.updateNativeHeadingBoundaries(candidate,updated.getDocxSha256(),flows,Collections.emptySet());List<Map<String,Object>> finalPairs=headingPairs(flows,after);proof.put("finalHeadingPairs",finalPairs);if(!splitHeadings(finalPairs).isEmpty())throw new IllegalStateException("HEADING_FLOW_FINAL_SPLIT");proof.put("headingFlowStatus","PASS");}
            proof.put("status","PASS");proof.remove("reason");proof.put("scope",flows.isEmpty()?"TOC caches for this exact DOCX and renderer profile; other fields and native Word pagination remain unverified":"TOC caches and all requested source-bound paragraph continuation page constraints for this exact DOCX and renderer profile; visual, Word, business and service acceptance remain separate");proof.put("finalConversionPass",conversions);proof.put("stablePointComparisonFromPass",conversions-1);proof.put("finalDocxSha256",last.getDocxSha256());proof.put("adoptedPdfSha256",last.getPdfSha256());proof.put("preservedPointCount",after.points.size());proof.put("finalPoints",after.points);proof.put("finalPages",after.pages);proof.put("finalActualTocPages",after.tocPages);
            return new Result(frozen,candidate,first,withProof(last,proof),proof);
        }catch(RuntimeException failure){proof.put("status","UNKNOWN");if(originalSourceSha256!=null)proof.put("headingFlowStatus","UNKNOWN");proof.put("reason",failure.getMessage());proof.put("failureType",failure.getClass().getSimpleName());proof.put("finalDocxSha256",DraftPdfConverter.sha256(frozen));return new Result(frozen,frozen,first,first==null?null:withProof(first,proof),proof);}
    }
    private static List<Map<String,Object>> headingPairs(List<NativeHeadingFlow> flows,Observation observation){List<Map<String,Object>> rows=new ArrayList<>();for(NativeHeadingFlow flow:flows){List<String> heading=observation.points.get(flow.getHeadingBookmark()),body=observation.points.get(flow.getBodyBookmark());if(heading==null||body==null||heading.size()!=3||body.size()!=3)throw new IllegalStateException("HEADING_FLOW_POINT_MISSING");if(Integer.parseInt(heading.get(0))>Integer.parseInt(body.get(0)))throw new IllegalStateException("HEADING_FLOW_REVERSED_PAGES");Map<String,Object> row=new LinkedHashMap<>();row.put("headingBookmark",flow.getHeadingBookmark());row.put("bodyBookmark",flow.getBodyBookmark());row.put("headingCoordinates",heading);row.put("bodyCoordinates",body);row.put("samePhysicalPage",heading.get(0).equals(body.get(0)));rows.add(row);}return rows;}
    private static Set<String> splitHeadings(List<Map<String,Object>> pairs){Set<String> split=new LinkedHashSet<>();for(Map<String,Object> pair:pairs)if(!Boolean.TRUE.equals(pair.get("samePhysicalPage")))split.add(String.valueOf(pair.get("headingBookmark")));return split;}

    private static void requirePair(byte[] docx,DraftPdfConversionResult pdf) {
        if(pdf==null||!DraftPdfConverter.sha256(docx).equals(pdf.getDocxSha256())||!DraftPdfConverter.sha256(pdf.getPdfBytes()).equals(pdf.getPdfSha256()))throw new IllegalStateException("TOC_CONVERSION_IDENTITY_MISMATCH");
    }
    private static DraftPdfConversionResult withProof(DraftPdfConversionResult pdf,Map<String,Object> proof) {
        Map<String,Object> manifest=new LinkedHashMap<>(pdf.getManifest());manifest.put("tocFinalization",new LinkedHashMap<>(proof));
        return new DraftPdfConversionResult(pdf.getPdfBytes(),manifest);
    }
    static final class Result {
        final byte[] compiled,docx;final DraftPdfConversionResult first,pdf;final Map<String,Object> proof;
        Result(byte[] compiled,byte[] docx,DraftPdfConversionResult first,DraftPdfConversionResult pdf,Map<String,Object> proof) {
            this.compiled=compiled.clone();this.docx=docx.clone();this.first=first;this.pdf=pdf;this.proof=Collections.unmodifiableMap(new LinkedHashMap<>(proof));
        }
        TemplateIndex index(){return new DocxTemplateEditor().inspect(docx);}
        /** An immutable existing cache may be reused only after checking this same measured pair. */
        Result useExisting(DraftPdfArtifact existing) {
            if(existing==null||pdf==null)return this;
            try {
                if(!pdf.getDocxSha256().equals(existing.getDocxSha256())||!pdf.getRenderProfileHash().equals(existing.getRenderProfileHash())||!DraftPdfConverter.sha256(existing.getPdfBytes()).equals(existing.getPdfSha256()))throw new IllegalStateException("TOC_EXISTING_CACHE_IDENTITY_MISMATCH");
                if("PASS".equals(proof.get("status"))) {
                    Observation saved=observe(existing.getPdfBytes(),index(),new DocxTemplateEditor().tocPageReferences(docx));
                    if(!saved.points.equals(proof.get("finalPoints"))||!saved.pages.equals(proof.get("finalPages"))||!saved.tocPages.equals(proof.get("finalActualTocPages")))throw new IllegalStateException("TOC_EXISTING_CACHE_LAYOUT_MISMATCH");
                }
                Map<String,Object> next=new LinkedHashMap<>(proof);next.put("adoptedPdfSha256",existing.getPdfSha256());next.put("reusedImmutablePdf",true);
                Map<String,Object> manifest=new LinkedHashMap<>(pdf.getManifest());manifest.put("pdfSha256",existing.getPdfSha256());
                return new Result(compiled,docx,first,withProof(new DraftPdfConversionResult(existing.getPdfBytes(),manifest),next),next);
            }catch(RuntimeException failure) {
                Map<String,Object> next=new LinkedHashMap<>(proof);next.put("status","UNKNOWN");if(next.containsKey("headingFlowStatus"))next.put("headingFlowStatus","UNKNOWN");next.put("reason",failure.getMessage());next.put("finalDocxSha256",DraftPdfConverter.sha256(compiled));
                return new Result(compiled,compiled,first,first,next);
            }
        }
        List<Object> ledger(List<Object> compiledLedger) {
            List<Object> rows=new ArrayList<>();String finalHash=DraftPdfConverter.sha256(docx);
            for(Object value:compiledLedger) {
                if(!(value instanceof Map)||!"native_layout".equals(((Map<?,?>)value).get("kind"))){rows.add(value);continue;}
                Map<String,Object> layout=new LinkedHashMap<>((Map<String,Object>)value);layout.put("docxSha256",finalHash);
                Object stories=layout.get("nativeStories");if(stories instanceof Map){Map<String,Object> next=new LinkedHashMap<>((Map<String,Object>)stories);next.put("docxSha256",finalHash);layout.put("nativeStories",next);}rows.add(layout);
            }
            rows.add(new LinkedHashMap<>(proof));return rows;
        }
    }
    private static final class Observation {
        final Map<String,List<String>> points=new LinkedHashMap<>();final Map<String,String> pages=new LinkedHashMap<>(),tocPages=new LinkedHashMap<>();
    }
    private static Observation observe(byte[] bytes,TemplateIndex index,List<TocPageReference> references) {
        try(PDDocument pdf=PDDocument.load(bytes)) {
            if(pdf.isEncrypted()||pdf.getNumberOfPages()==0)throw new IOException("TOC_PDF_UNREADABLE");
            Observation result=new Observation();Set<String> targets=new LinkedHashSet<>(),names=new LinkedHashSet<>();
            for(TocPageReference reference:references){targets.add(reference.getTargetBookmark());names.add(reference.getTargetBookmark());}
            for(Feature feature:index.getFeatures())if("BOOKMARK".equals(feature.getKind())&&feature.getName()!=null&&feature.getName().matches("(?:CS|CL)[A-Za-z0-9]{1,38}")) {
                if(!feature.isComplete()||!names.add(feature.getName()))throw new IOException("TOC_BINDING_NAME_AMBIGUOUS");
            }
            Map<Integer,String> footers=new HashMap<>();
            for(String name:names) {
                PDPageDestination selected=null;int matches=0;
                List<String> wireNames=name.startsWith("_")?Arrays.asList(name,name.replace("_","5F")):Collections.singletonList(name);
                for(String wire:wireNames){PDPageDestination found=pdf.getDocumentCatalog().findNamedDestinationPage(new PDNamedDestination(wire));if(found!=null){selected=found;matches++;}}
                if(matches!=1||!(selected instanceof PDPageXYZDestination))throw new IOException("TOC_NAMED_DESTINATION_MISSING_OR_AMBIGUOUS: "+name);
                int page=selected.retrievePageNumber();if(page<0||page>=pdf.getNumberOfPages())throw new IOException("TOC_DESTINATION_PAGE_INVALID");
                COSArray coordinates=selected.getCOSObject();COSBase left=coordinates.getObject(2),top=coordinates.getObject(3);
                if(!(left instanceof COSNumber)||!(top instanceof COSNumber))throw new IOException("TOC_DESTINATION_NOT_AN_OBSERVED_POINT");
                PDPage targetPage=pdf.getPage(page);float x=((COSNumber)left).floatValue(),y=((COSNumber)top).floatValue();
                if(targetPage.getRotation()!=0||!Float.isFinite(x)||!Float.isFinite(y)||x<targetPage.getCropBox().getLowerLeftX()||x>targetPage.getCropBox().getUpperRightX()||y<targetPage.getCropBox().getLowerLeftY()||y>targetPage.getCropBox().getUpperRightY())throw new IOException("TOC_DESTINATION_GEOMETRY_UNSUPPORTED");
                result.points.put(name,Arrays.asList(String.valueOf(page+1),number((COSNumber)left),number((COSNumber)top)));
                if(targets.contains(name)){if(!footers.containsKey(page))footers.put(page,footer(pdf,page));result.pages.put(name,footers.get(page));}
            }
            for(TocPageReference reference:references)result.tocPages.put(reference.getTargetBookmark(),tocRowPage(pdf,index,reference,result.points));
            return result;
        }catch(IOException invalid){throw new IllegalStateException(invalid.getMessage(),invalid);}
    }
    private static String number(COSNumber value){return new BigDecimal(String.valueOf(value.doubleValue())).stripTrailingZeros().toPlainString();}
    private static String tocRowPage(PDDocument pdf,TemplateIndex index,TocPageReference reference,Map<String,List<String>> points) throws IOException {
        Paragraph row=null;for(Paragraph paragraph:index.getMainParagraphs())if(reference.getCacheNodeId().startsWith(paragraph.getId()+"/")&&(row==null||paragraph.getId().length()>row.getId().length()))row=paragraph;
        if(row==null)throw new IOException("TOC_ROW_PARAGRAPH_UNBOUND");Map<String,String> rowMarkers=new HashMap<>();String next=null;
        for(Feature feature:index.getFeatures())if("BOOKMARK".equals(feature.getKind())&&feature.getName()!=null&&feature.getName().matches("CS[A-Za-z0-9]{1,38}")) {
            String paragraph=feature.getStartId().substring(0,feature.getStartId().lastIndexOf('/'));if(rowMarkers.put(paragraph,feature.getName())!=null)throw new IOException("TOC_ROW_BINDING_AMBIGUOUS");
        }
        String marker=rowMarkers.get(row.getId());for(Paragraph paragraph:index.getMainParagraphs())if(paragraph.getOrdinal()>row.getOrdinal()&&rowMarkers.containsKey(paragraph.getId())){next=rowMarkers.get(paragraph.getId());break;}
        if(marker==null||next==null)throw new IOException("TOC_ROW_BINDING_UNAVAILABLE");List<String> start=points.get(marker),end=points.get(next);
        if(start==null||end==null)throw new IOException("TOC_ROW_PDF_POINT_UNAVAILABLE");int page=Integer.parseInt(start.get(0))-1;
        float height=pdf.getPage(page).getCropBox().getHeight(),from=height-Float.parseFloat(start.get(2)),to=Integer.parseInt(end.get(0))==page+1?height-Float.parseFloat(end.get(2)):height-60;
        if(Integer.parseInt(end.get(0))<page+1||to<=from)throw new IOException("TOC_ROW_PDF_RANGE_UNSUPPORTED");List<TextPosition> glyphs=new ArrayList<>();
        PDFTextStripper stripper=new PDFTextStripper(){@Override protected void processTextPosition(TextPosition position){if(position.getYDirAdj()>=from-1&&position.getYDirAdj()<to-1)glyphs.add(position);super.processTextPosition(position);}};
        stripper.setStartPage(page+1);stripper.setEndPage(page+1);stripper.getText(pdf);glyphs.sort(Comparator.comparingDouble(TextPosition::getYDirAdj).thenComparingDouble(TextPosition::getXDirAdj));
        StringBuilder text=new StringBuilder();for(TextPosition glyph:glyphs)text.append(glyph.getUnicode());
        java.util.regex.Matcher value=java.util.regex.Pattern.compile("\\.{2,}\\s*([1-9][0-9]*)\\s*$").matcher(text);
        if(!value.find())throw new IOException("TOC_DISPLAYED_ROW_PAGE_UNAVAILABLE: "+reference.getTargetBookmark());return value.group(1);
    }
    private static String footer(PDDocument pdf,int page) throws IOException {
        float height=pdf.getPage(page).getCropBox().getHeight();List<TextPosition> glyphs=new ArrayList<>();
        PDFTextStripper stripper=new PDFTextStripper(){@Override protected void processTextPosition(TextPosition position){if(position.getYDirAdj()>=height-60)glyphs.add(position);super.processTextPosition(position);}};
        stripper.setStartPage(page+1);stripper.setEndPage(page+1);stripper.getText(pdf);
        glyphs.sort(Comparator.comparingDouble(TextPosition::getYDirAdj).thenComparingDouble(TextPosition::getXDirAdj));
        List<String> words=new ArrayList<>();StringBuilder word=new StringBuilder();TextPosition previous=null;
        for(TextPosition glyph:glyphs) {
            if(previous!=null&&(Math.abs(previous.getYDirAdj()-glyph.getYDirAdj())>1||glyph.getXDirAdj()-previous.getXDirAdj()-previous.getWidthDirAdj()>Math.max(2,previous.getWidthOfSpace()/2))) {if(word.length()>0)words.add(word.toString());word.setLength(0);}
            word.append(glyph.getUnicode());previous=glyph;
        }
        if(word.length()>0)words.add(word.toString());List<String> pages=new ArrayList<>();for(String value:words)if(value.trim().matches("[1-9][0-9]*"))pages.add(value.trim());
        if(pages.size()!=1)throw new IOException("TOC_DISPLAYED_FOOTER_MISSING_OR_AMBIGUOUS: "+(page+1));return pages.get(0);
    }
}
