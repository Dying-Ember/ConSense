package com.consense.document;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;

/** Read existing PDF operators/appearance streams; never regenerate a form appearance. */
final class PdfNativeSupport {
    private PdfNativeSupport() {}

    /** A painted path is an observation gap, never proof of a table, control, or drawing identity. */
    static final class PaintCollector {
        private final PDPage page;private final PdfPageEvidence evidence;
        private final ObjectMapper json;
        private final List<JsonNode> path=new ArrayList<>();
        private double left=Double.POSITIVE_INFINITY,bottom=Double.POSITIVE_INFINITY,right=Double.NEGATIVE_INFINITY,top=Double.NEGATIVE_INFINITY;
        PaintCollector(PDPage page,PdfPageEvidence evidence,ObjectMapper json){this.page=page;this.evidence=evidence;this.json=json;}
        static final class State {
            final List<JsonNode> path;final double[] bounds;
            State(List<JsonNode> path,double[] bounds){this.path=path;this.bounds=bounds;}
        }
        State enterChild(){State saved=new State(new ArrayList<>(path),new double[]{left,bottom,right,top});clear();return saved;}
        void exitChild(State saved){clear();path.addAll(saved.path);left=saved.bounds[0];bottom=saved.bounds[1];right=saved.bounds[2];top=saved.bounds[3];}
        void accept(Operator operator,List<COSBase> operands,PDGraphicsState state) {
            String name=operator.getName();
            if(Arrays.asList("m","l","c","v","y","re","h","W","W*").contains(name)) {
                double[] values=new double[operands.size()];for(int index=0;index<values.length;index++) {
                    if(!(operands.get(index) instanceof COSNumber))return;values[index]=((COSNumber)operands.get(index)).doubleValue();
                }
                ObjectNode command=json.createObjectNode();command.put("operator",name);command.put("id","pdf-page/"+evidence.getPageNo()+"/vector-path-operator/"+evidence.getVectorPathOperators().size());
                command.set("operands",json.valueToTree(values));command.set("currentTransformationMatrix",json.valueToTree(matrix(state.getCurrentTransformationMatrix())));
                path.add(command);evidence.getVectorPathOperators().add(command);
                if("re".equals(name) && values.length==4) {
                    point(values[0],values[1],state);point(values[0]+values[2],values[1],state);
                    point(values[0]+values[2],values[1]+values[3],state);point(values[0],values[1]+values[3],state);
                } else if(Arrays.asList("m","l","c","v","y").contains(name))for(int index=0;index+1<values.length;index+=2)point(values[index],values[index+1],state);
            } else if(Arrays.asList("S","s","f","F","f*","B","B*","b","b*").contains(name)) {
                if(!path.isEmpty() && left!=Double.POSITIVE_INFINITY) {
                    PDRectangle crop=page.getCropBox();double[] box=rotate(new double[]{(left-crop.getLowerLeftX())/crop.getWidth(),
                            (crop.getUpperRightY()-top)/crop.getHeight(),(right-left)/crop.getWidth(),(top-bottom)/crop.getHeight()},page.getRotation());
                    boolean finite=true;for(double value:box)finite&=Double.isFinite(value);
                    boolean intersects=right>=crop.getLowerLeftX() && left<=crop.getUpperRightX() && top>=crop.getLowerLeftY() && bottom<=crop.getUpperRightY();
                    boolean stroked=Arrays.asList("S","s","B","B*","b","b*").contains(name),filled=!Arrays.asList("S","s").contains(name);
                    boolean paintedAlpha=stroked && state.getAlphaConstant()>0 || filled && state.getNonStrokeAlphaConstant()>0;
                    ObjectNode scope=json.createObjectNode();scope.put("id","pdf-page/"+evidence.getPageNo()+"/vector-paint/"+evidence.getPaintedVectorRegions().size());
                    scope.put("paintOperator",name);scope.set("pathOperators",json.valueToTree(path));scope.set("bbox",finite?json.valueToTree(box):json.nullNode());
                    scope.set("pathBoundsOriginalPdf",json.valueToTree(new double[]{left,bottom,right-left,top-bottom}));
                    scope.put("coordinateSpace","normalized_rendered_crop_top_left");scope.put("semanticKind","unknown");
                    scope.put("scopeMeaning","path/control-point envelope; exact curve, clipping, pixel visibility and topology unknown");
                    scope.put("strokeAlpha",state.getAlphaConstant());scope.put("fillAlpha",state.getNonStrokeAlphaConstant());scope.put("lineWidth",state.getLineWidth());
                    scope.put("requiresVisualObservation",!finite || intersects && paintedAlpha);scope.put("geometryStatus",finite?"observed_without_clipping":"invalid_unknown");
                    evidence.getPaintedVectorRegions().add(scope);
                }
                clear();
            } else if("n".equals(name))clear();
        }
        private void point(double x,double y,PDGraphicsState state) {
            java.awt.geom.Point2D.Float p=state.getCurrentTransformationMatrix().transformPoint((float)x,(float)y);
            left=Math.min(left,p.x);right=Math.max(right,p.x);bottom=Math.min(bottom,p.y);top=Math.max(top,p.y);
        }
        private void clear(){path.clear();left=bottom=Double.POSITIVE_INFINITY;right=top=Double.NEGATIVE_INFINITY;}
    }

    static PdfPageEvidence page(int pageNo, PDPage page) {
        PdfPageEvidence result = new PdfPageEvidence();
        result.setPageNo(pageNo); result.setRotation(page.getRotation());
        result.setCropBox(rect(page.getCropBox())); result.setUserUnit(page.getUserUnit());
        result.setMediaBox(rect(page.getMediaBox()));
        result.getLimitations().add("Operator visibility does not verify raster appearance, later occlusion, color contrast, optional-content state, or exact clipping of each glyph.");
        return result;
    }

    static PdfPageEvidence.PdfGlyph glyph(TextPosition p, PDGraphicsState state, PDPage page, String id) {
        PdfPageEvidence.PdfGlyph result = new PdfPageEvidence.PdfGlyph();
        result.setId(id); result.setUnicode(p.getUnicode()); result.setCharacterCodes(p.getCharacterCodes());
        result.setFontName(p.getFont() == null ? null : p.getFont().getName());
        result.setTextMatrix(matrix(p.getTextMatrix()));
        result.setCurrentTransformationMatrix(matrix(state.getCurrentTransformationMatrix()));
        result.setPdfboxPosition(new double[]{p.getX(), p.getY(), p.getWidth(), p.getHeight(),
                p.getXDirAdj(), p.getYDirAdj(), p.getWidthDirAdj(), p.getHeightDir(), p.getPageWidth(), p.getPageHeight()});
        result.setFontSize(p.getFontSize()); result.setFontSizeInPt(p.getFontSizeInPt());
        result.setXScale(p.getXScale()); result.setYScale(p.getYScale());
        result.setWidthOfSpace(p.getWidthOfSpace()); result.setDirection(p.getDir());
        if (p.getFont() != null) { result.setFontSubtype(p.getFont().getSubType()); result.setFontEmbedded(p.getFont().isEmbedded()); }
        result.setTextStateFontSize(state.getTextState().getFontSize());
        result.setCharacterSpacing(state.getTextState().getCharacterSpacing()); result.setWordSpacing(state.getTextState().getWordSpacing());
        result.setHorizontalScaling(state.getTextState().getHorizontalScaling()); result.setLeading(state.getTextState().getLeading());
        result.setRise(state.getTextState().getRise()); result.setLineWidth(state.getLineWidth());
        result.setFillColorSpace(state.getNonStrokingColor().getColorSpace().getName());
        result.setStrokeColorSpace(state.getStrokingColor().getColorSpace().getName());
        result.setFillColorComponents(doubles(state.getNonStrokingColor().getComponents()));
        result.setStrokeColorComponents(doubles(state.getStrokingColor().getComponents()));
        java.awt.geom.Rectangle2D clip = state.getCurrentClippingPath().getBounds2D();
        result.setClippingBoundsPdf(new double[]{clip.getX(), clip.getY(), clip.getWidth(), clip.getHeight()});
        result.getLimitations().add("PDFBox position and clipping bounding rectangle are observed values, not exact glyph outlines or clipping intersection.");
        result.getLimitations().add("Raster appearance, later occlusion, optional content and effective reading order remain unknown.");
        RenderingMode mode = state.getTextState().getRenderingMode();
        result.setRenderingMode(mode.intValue());
        result.setFillAlpha(state.getNonStrokeAlphaConstant()); result.setStrokeAlpha(state.getAlphaConstant());
        int direction = Math.round(p.getDir()); boolean quarter = Math.floorMod(direction, 180) != 0;
        double width = quarter ? p.getPageHeight() : p.getPageWidth();
        double height = quarter ? p.getPageWidth() : p.getPageHeight();
        double[] b = width > 0 && height > 0 ? new double[]{p.getXDirAdj() / width,
                (p.getYDirAdj() - p.getHeightDir()) / height, p.getWidthDirAdj() / width, p.getHeightDir() / height} : null;
        result.setBbox(b == null ? null : rotate(b, page.getRotation() - direction));
        boolean painted = mode.isFill() || mode.isStroke();
        boolean opaque = (mode.isFill() && result.getFillAlpha() > 0) || (mode.isStroke() && result.getStrokeAlpha() > 0);
        boolean inCrop = b == null || (b[0] + b[2] > 0 && b[1] + b[3] > 0 && b[0] < 1 && b[1] < 1);
        result.setVisibilityStatus(!painted ? "hidden_rendering_mode" : !opaque ? "hidden_zero_alpha"
                : !inCrop ? "outside_crop" : "operator_visible_unverified");
        result.setEligibleForNativeText(painted && opaque && inCrop);
        return result;
    }

    static void collectForms(PDDocument document, List<PdfPageEvidence> pages) throws IOException {
        // getAcroForm() can apply PDFBox fixups. Use the existing dictionary without viewer synthesis.
        COSDictionary dictionary = (COSDictionary) document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.ACRO_FORM);
        if (dictionary == null) return;
        PDAcroForm form = new PDAcroForm(document, dictionary);
        int fieldIndex = 0;
        for (PDField field : form.getFieldTree()) {
            List<PDAnnotationWidget> widgets = field.getWidgets();
            if (widgets.isEmpty()) widgets = java.util.Collections.singletonList(null);
            int widgetIndex = 0;
            for (PDAnnotationWidget widget : widgets) {
                PdfPageEvidence.PdfFormField item = new PdfPageEvidence.PdfFormField();
                item.setId("pdf-form/" + fieldIndex + "/widget/" + widgetIndex++);
                item.setFieldName(field.getFullyQualifiedName()); item.setFieldType(field.getFieldType());
                item.setNeedAppearances(form.getNeedAppearances());
                try { item.setFieldValue(field.getValueAsString()); }
                catch (RuntimeException failure) { item.setFieldValue(""); item.getLimitations().add("Field value could not be decoded: " + failure.getClass().getSimpleName()); }
                int pageNo = widget == null ? 0 : widgetPage(document, widget);
                item.setPageNo(pageNo == 0 ? null : pageNo);
                PDPage page = pageNo == 0 ? null : document.getPage(pageNo - 1);
                boolean visible = widget != null && !widget.isHidden() && !widget.isInvisible() && !widget.isNoView();
                item.setVisibilityStatus(widget == null ? "widget_missing" : !visible ? "hidden_widget"
                        : page == null ? "widget_page_unknown" : "operator_visible_unverified");
                if (widget != null) {
                    item.setAnnotationFlags(widget.getAnnotationFlags());
                    item.setRectanglePdf(rect(widget.getRectangle()));
                    if (page != null) item.setBbox(widgetBox(widget.getRectangle(), page));
                    item.setAppearanceState(widget.getAppearanceState() == null ? null : widget.getAppearanceState().getName());
                    PDAppearanceStream appearance = widget.getNormalAppearanceStream();
                    item.setAppearancePresent(appearance != null);
                    if (appearance != null) {
                        item.setAppearanceBbox(rect(appearance.getBBox())); item.setAppearanceMatrix(matrix(appearance.getMatrix()));
                        item.setAppearanceStreamObservationStatus("existing_normal_AP_bytes_observed");
                        try (java.io.InputStream raw = appearance.getCOSObject().createRawInputStream();
                             java.io.InputStream decoded = appearance.getCOSObject().createInputStream()) {
                            item.setAppearanceRawStreamBytesBase64(java.util.Base64.getEncoder().encodeToString(readBytes(raw)));
                            item.setAppearanceDecodedStreamBytesBase64(java.util.Base64.getEncoder().encodeToString(readBytes(decoded)));
                        } catch (IOException | RuntimeException failure) {
                            item.setAppearanceStreamObservationStatus("partial_existing_normal_AP_bytes_failure");
                            item.getLimitations().add("Existing AP byte observation failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                        }
                        if (page != null) {
                            AppearanceStripper stripper = null;
                            try {
                                stripper = new AppearanceStripper(page, item.getId());
                                stripper.processChild(appearance, page);
                                item.setAppearanceGlyphObservationStatus("observed_existing_normal_AP_operators_unreviewed");
                            } catch (IOException | RuntimeException failure) {
                                item.setAppearanceGlyphObservationStatus("partial_existing_normal_AP_operator_failure");
                                item.getLimitations().add("Appearance extraction failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                            } finally {
                                if (stripper != null) { item.setAppearanceGlyphs(stripper.glyphs); item.setAppearanceValue(stripper.text.toString().trim()); }
                            }
                        } else {
                            item.setAppearanceGlyphObservationStatus("not_observed_widget_page_unknown");
                            item.getLimitations().add("Existing AP glyphs unobserved because no physical widget page is established; stream bytes retained without a fabricated page transform.");
                        }
                    }
                }
                String value = comparable(item.getFieldValue()), appearance = comparable(item.getAppearanceValue());
                item.setConflict(item.isAppearancePresent() && !appearance.isEmpty() && !value.equals(appearance));
                boolean partialAppearance = item.getAppearanceStreamObservationStatus().startsWith("partial_")
                        || item.getAppearanceGlyphObservationStatus().startsWith("partial_");
                item.setValueStatus(partialAppearance ? "appearance_observation_partial_unknown" : item.isConflict() ? "field_appearance_conflict"
                        : !visible ? "not_visible" : !item.isAppearancePresent() ? "appearance_missing"
                        : page == null ? "widget_page_unknown"
                        : appearance.isEmpty() ? "empty_or_non_text_appearance" : "appearance_text_unverified");
                item.getLimitations().add("Stored field value and existing appearance are independent evidence; no appearance regeneration or visual confirmation was performed.");
                item.getLimitations().add("Only the selected existing normal AP stream is interpreted; rollover/down and other appearance states remain unobserved in retained original PDF bytes.");
                PdfPageEvidence owner;
                if (pageNo > 0) owner = pages.get(pageNo - 1);
                else {
                    owner = pages.stream().filter(p -> p.getPageNo() == 0).findFirst().orElse(null);
                    if (owner == null) { owner = new PdfPageEvidence(); owner.setPageNo(0); owner.getLimitations().add("Form widgets below have no established physical page."); pages.add(owner); }
                }
                owner.getFormFields().add(item);
            }
            fieldIndex++;
        }
    }

    private static int widgetPage(PDDocument document, PDAnnotationWidget widget) throws IOException {
        int index = 0;
        for (PDPage page : document.getPages()) {
            index++;
            if (widget.getPage() != null && widget.getPage().getCOSObject() == page.getCOSObject()) return index;
            for (PDAnnotation annotation : page.getAnnotations())
                if (annotation.getCOSObject() == widget.getCOSObject()) return index;
        }
        return 0;
    }

    private static final class AppearanceStripper extends PDFTextStripper {
        final List<PdfPageEvidence.PdfGlyph> glyphs = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        final PDPage page; final String id;
        AppearanceStripper(PDPage page, String id) throws IOException { this.page = page; this.id = id; }
        void processChild(PDAppearanceStream stream, PDPage page) throws IOException {
            // LegacyPDFStreamEngine initializes its private page geometry only in processPage,
            // not processChildStream. Initialize with an empty transient frame, never the
            // actual page content or a regenerated widget appearance.
            PDPage frame = new PDPage(page.getMediaBox()); frame.setCropBox(page.getCropBox());
            frame.setRotation(page.getRotation()); frame.setUserUnit(page.getUserUnit());
            output = new java.io.StringWriter(); setStartPage(0); super.processPage(frame);
            processChildStream(stream, page);
        }
        @Override protected void processTextPosition(TextPosition position) {
            PdfPageEvidence.PdfGlyph glyph = glyph(position, getGraphicsState(), page, id + "/glyph/" + glyphs.size());
            // Appearance positions are stream-local; their widget mapping is stored independently.
            glyph.setBbox(null); glyph.setCoordinateSpace("appearance_stream_local_matrix_no_page_box");
            boolean painted = glyph.getRenderingMode() != 3 && glyph.getRenderingMode() != 7;
            if ("outside_crop".equals(glyph.getVisibilityStatus()) && painted) {
                glyph.setVisibilityStatus("operator_visible_unverified"); glyph.setEligibleForNativeText(true);
            }
            glyphs.add(glyph);
            if (glyph.isEligibleForNativeText()) text.append(position.getUnicode());
        }
    }

    static double[] rect(PDRectangle r) { return r == null ? null : new double[]{r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight()}; }
    static double[] matrix(Matrix m) { return m == null ? null : new double[]{m.getScaleX(), m.getShearY(), m.getShearX(), m.getScaleY(), m.getTranslateX(), m.getTranslateY()}; }
    private static double[] doubles(float[] values) { double[] out = new double[values.length]; for (int i = 0; i < values.length; i++) out[i] = values[i]; return out; }
    private static byte[] readBytes(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
        while ((count = input.read(block)) != -1) out.write(block, 0, count); return out.toByteArray();
    }
    private static String comparable(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private static double[] widgetBox(PDRectangle r, PDPage page) {
        PDRectangle crop = page.getCropBox();
        if (r == null || crop.getWidth() <= 0 || crop.getHeight() <= 0) return null;
        return rotate(new double[]{(r.getLowerLeftX() - crop.getLowerLeftX()) / crop.getWidth(),
                (crop.getUpperRightY() - r.getUpperRightY()) / crop.getHeight(), r.getWidth() / crop.getWidth(), r.getHeight() / crop.getHeight()}, page.getRotation());
    }
    static double[] rotate(double[] b, int rotation) {
        switch (Math.floorMod(rotation, 360)) {
            case 90: return new double[]{1 - b[1] - b[3], b[0], b[3], b[2]};
            case 180: return new double[]{1 - b[0] - b[2], 1 - b[1] - b[3], b[2], b[3]};
            case 270: return new double[]{b[1], 1 - b[0] - b[2], b[3], b[2]};
            default: return b;
        }
    }
}
