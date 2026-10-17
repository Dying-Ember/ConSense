package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;
import org.w3c.dom.Node;
import java.io.InputStream;
import java.util.*;

/** Source run formatting and cached field roles; neither typography nor fields are recalculated. */
final class WordInlineSemantics {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MC="http://schemas.openxmlformats.org/markup-compatibility/2006";
    private final Styles styles;
    private final Map<Object,Node> sources;
    private final Map<Node,ObjectNode> paragraphTexts;
    private final Deque<Field> fields=new ArrayDeque<>();

    WordInlineSemantics(XWPFDocument document,String sourceSha,Map<Object,Node> sources)throws Exception {
        this.styles=new Styles(document,sourceSha);this.sources=sources;this.paragraphTexts=new IdentityHashMap<>();
    }
    private WordInlineSemantics(Styles styles,Map<Object,Node> sources,Map<Node,ObjectNode> paragraphTexts){this.styles=styles;this.sources=sources;this.paragraphTexts=paragraphTexts;}
    WordInlineSemantics newStory(){return new WordInlineSemantics(styles,sources,paragraphTexts);}
    void finish(){for(Field field:fields)field.value.put("structureStatus","unknown");fields.clear();}

    void paragraph(Node node,String text,String original,String deleted,String struck){
        ObjectNode out=object();out.put("text",text);out.put("originalText",original);out.put("deletedText",deleted);out.put("strikeText",struck);paragraphTexts.put(node,out);
    }
    JsonNode paragraph(Node node){return paragraphTexts.get(node);}
    void preNormalizedParagraph(Node node,String text,String original,String deleted,String struck){
        ObjectNode paragraph=paragraphTexts.get(node);if(paragraph==null)return;
        ObjectNode raw=paragraph.putObject("preNormalizedTextObservation");
        raw.put("status","observed_parser_channels_before_whitespace_normalization");
        raw.put("offsetConvention","utf16_code_units_in_named_pre_normalized_parser_channel_start_inclusive_end_exclusive");
        raw.put("meaning","Existing parser projection including resolved labels, control conversions and trailing paragraph newline; not raw XML or an original-package editing coordinate");
        raw.put("text",text);raw.put("originalText",original);raw.put("deletedText",deleted);raw.put("strikeText",struck);
    }

    ObjectNode field(Node node) {
        if(word(node,"fldSimple")) {
            Field field=new Field(node,true);field.inResult=true;String instruction=attribute(node,W,"instr");field.invalid=instruction==null;
            field.instruction.append(instruction==null?"":instruction);if(field.invalid)field.value.put("reason","simple_field_instruction_absent");
            field.update();fields.push(field);return field.value;
        }
        if(!word(node,"fldChar"))return null;
        String type=attribute(node,W,"fldCharType");
        if("begin".equals(type)){Field field=new Field(node,false);field.control(node);fields.push(field);return field.value;}
        if(fields.isEmpty()) {
            ObjectNode orphan=object();orphan.put("kind","complex");orphan.put("role","unknown");orphan.put("rawInstruction","");
            orphan.put("structureStatus","unknown");orphan.put("resultStatus","cached_not_recalculated");orphan.put("reason","field_control_without_begin");orphan.putArray("controls").addObject().put("controlType",type);sources.put(orphan,node);return orphan;
        }
        Field active=fields.peek();active.control(node);
        if("separate".equals(type)&&!active.simple&&!active.inResult){active.inResult=true;active.update();}
        else if("end".equals(type)&&!active.simple){fields.pop();active.value.put("structureStatus",active.inResult&&!active.invalid?"complete":"unknown");active.update();}
        else {active.invalid=true;active.value.put("structureStatus","unknown");active.value.put("reason","invalid_or_duplicate_field_control");}
        return active.value;
    }
    void endSimple(){boolean nestedUnclosed=false;while(!fields.isEmpty()){Field field=fields.pop();if(field.simple){field.value.put("structureStatus",nestedUnclosed||field.invalid?"unknown":"complete");field.update();return;}nestedUnclosed=true;field.value.put("structureStatus","unknown");}}
    ObjectNode instruction(Node node) {
        if(!word(node,"instrText")||fields.isEmpty())return null;
        Field field=fields.peek();if(field.inResult)return null;
        String text=text(node);field.instruction.append(text);field.update();ObjectNode fragment=field.fragments.addObject();fragment.put("text",text);sources.put(fragment,node);
        return field.value;
    }
    ObjectNode span(Node node,boolean deleted,boolean struck) {
        if(!word(node,"t")&&!word(node,"delText"))return null;
        Format format=styles.format(node,sources);String role=null;Field roleField=null;
        for(Field field:fields)if(field.inResult){if("toc".equals(field.value.path("role").asText())){role="toc_result";roleField=field;break;}if(roleField==null){role="field_result";roleField=field;}}
        if(!format.meaningful()&&role==null)return null;
        ObjectNode span=object();String text=text(node);span.put("sourceText",text);span.put("deleted",deleted||word(node,"delText"));span.put("struck",struck);
        span.putObject("sourceRange").put("offsetUnit","utf16").put("start",0).put("end",text.length());
        span.set("format",format.json());span.set("styleTrace",format.trace);if(role!=null){span.put("fieldRole",role);ObjectNode reference=span.putObject("fieldSourcePosition");sources.put(reference,roleField.source);}
        sources.put(span,node);return span;
    }
    private final class Field {
        final ObjectNode value=object();final StringBuilder instruction=new StringBuilder();final ArrayNode fragments=value.putArray("instructionFragments"),controls=value.putArray("controls");final boolean simple;final Node source;boolean inResult,invalid;
        Field(Node node,boolean simple){this.simple=simple;this.source=node;value.put("kind",simple?"simple":"complex");value.put("structureStatus","unknown");value.put("resultStatus","cached_not_recalculated");sources.put(value,node);update();}
        void control(Node node){ObjectNode control=controls.addObject();control.put("controlType",attribute(node,W,"fldCharType"));sources.put(control,node);}
        void update(){String raw=instruction.toString();value.put("rawInstruction",raw);String trimmed=raw.trim();String code=trimmed.isEmpty()?"":trimmed.split("\\s+",2)[0];value.put("role","TOC".equalsIgnoreCase(code)?"toc":code.isEmpty()?"unknown":"field");}
    }

    static final class Alternative {
        Node selected;final ObjectNode evidence=object();
    }
    static Alternative alternative(Node node) {
        Alternative out=new Alternative();ArrayNode branches=out.evidence.putArray("branches");List<Node> choices=new ArrayList<>();Node fallback=null;boolean invalid=false;
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()==Node.ELEMENT_NODE){
            boolean choice=MC.equals(child.getNamespaceURI())&&"Choice".equals(child.getLocalName()),isFallback=MC.equals(child.getNamespaceURI())&&"Fallback".equals(child.getLocalName());
            ObjectNode branch=branches.addObject();branch.put("kind",child.getLocalName());branch.put("ordinalZeroBased",branches.size()-1);
            try{branch.put("nativeXmlSha256",DocumentParseProbe.sha256(org.apache.xmlbeans.XmlObject.Factory.parse(child).xmlText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));branch.put("hashEncoding","ooxml_dom_xml_utf8");}
            catch(Exception unavailable){branch.put("hashStatus","unavailable_original_package_retained");}
            if(choice){if(fallback!=null)invalid=true;choices.add(child);String requires=attribute(child,null,"Requires");branch.put("requires",requires);boolean supported=requires!=null&&!requires.trim().isEmpty();if(!supported)invalid=true;ArrayNode namespaces=branch.putArray("requiredNamespaces");
                if(requires!=null)for(String prefix:requires.trim().split("\\s+")){String namespace=namespace(child,prefix);ObjectNode requirement=namespaces.addObject();requirement.put("prefix",prefix);requirement.put("namespace",namespace);
                    if(namespace==null||namespace.isEmpty())invalid=true;
                    supported&=W.equals(namespace)||"http://schemas.openxmlformats.org/officeDocument/2006/math".equals(namespace);}
                branch.put("supported",supported);if(out.selected==null&&supported)out.selected=child;
            }else if(isFallback){if(fallback!=null)invalid=true;fallback=child;}else invalid=true;
        }
        String status="supported_choice";
        if(invalid||choices.isEmpty()){out.selected=null;status="unknown_invalid_branches";}
        else if(out.selected==null&&fallback!=null){out.selected=fallback;status="fallback";}
        else if(out.selected==null&&!choices.isEmpty()){
            String signature=null;boolean equivalent=true;
            for(Node choice:choices){String current=plainSignature(choice);if(current==null||(signature!=null&&!signature.equals(current))){equivalent=false;break;}signature=current;}
            if(equivalent){out.selected=choices.get(0);status="equivalent_plain_text";}else status="unknown_no_supported_branch";
        }else if(out.selected==null)status="unknown_no_branch";
        out.evidence.put("selectionStatus",status);out.evidence.put("semanticStatus",out.selected==null?"unknown":"selected_native_content");
        if(out.selected!=null){int ordinal=0;for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()==Node.ELEMENT_NODE){if(child==out.selected){out.evidence.put("selectedOrdinalZeroBased",ordinal);break;}ordinal++;}}
        return out;
    }
    private static String plainSignature(Node branch){StringBuilder out=new StringBuilder();for(Node node=branch.getFirstChild();node!=null;node=node.getNextSibling())if(!plain(node,out))return null;return out.toString();}
    private static boolean plain(Node node,StringBuilder out){
        if(node.getNodeType()==Node.TEXT_NODE||node.getNodeType()==Node.CDATA_SECTION_NODE){if(!node.getNodeValue().trim().isEmpty())return false;return true;}
        if(node.getNodeType()!=Node.ELEMENT_NODE)return false;
        if(!W.equals(node.getNamespaceURI())||!Arrays.asList("p","r","t").contains(node.getLocalName()))return false;
        for(int i=0;i<node.getAttributes().getLength();i++){Node attr=node.getAttributes().item(i);if(!"http://www.w3.org/2000/xmlns/".equals(attr.getNamespaceURI())&&!(word(node,"t")&&"http://www.w3.org/XML/1998/namespace".equals(attr.getNamespaceURI())&&"space".equals(attr.getLocalName())))return false;}
        out.append('<').append(node.getLocalName()).append(" xml:space=").append(attribute(node,"http://www.w3.org/XML/1998/namespace","space")).append('>');
        if(word(node,"t")){for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()!=Node.TEXT_NODE&&child.getNodeType()!=Node.CDATA_SECTION_NODE)return false;String value=text(node);out.append(value.length()).append(':').append(value);}
        else for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(!plain(child,out))return false;
        out.append("</").append(node.getLocalName()).append('>');return true;
    }
    private static String namespace(Node node,String prefix){if("xml".equals(prefix))return "http://www.w3.org/XML/1998/namespace";for(Node parent=node;parent!=null;parent=parent.getParentNode())if(parent.getAttributes()!=null){Node value=parent.getAttributes().getNamedItem("xmlns:"+prefix);if(value!=null)return value.getNodeValue();}return null;}

    private static final class Format {
        Boolean italic=false;String vertical="baseline";boolean italicSeen,verticalSeen;final ArrayNode trace=JsonUtils.mapper().createArrayNode();
        void unknown(String reason){italic=null;vertical=null;italicSeen=true;verticalSeen=true;trace.addObject().put("status","unknown").put("reason",reason);}
        boolean meaningful(){return italicSeen||verticalSeen||italic==null||vertical==null||Boolean.TRUE.equals(italic)||!"baseline".equals(vertical);}
        ObjectNode json(){ObjectNode out=object();if(italicSeen||italic==null||Boolean.TRUE.equals(italic)){ObjectNode p=out.putObject("italic");p.put("status",italic==null?"unknown":"resolved");if(italic==null)p.putNull("value");else p.put("value",italic);}
            if(verticalSeen||vertical==null||!"baseline".equals(vertical)){ObjectNode p=out.putObject("verticalAlign");p.put("status",vertical==null?"unknown":"resolved");p.put("value",vertical);}return out;}
    }
    private static final class Styles {
        final Map<String,Node> byId=new HashMap<>();final Map<Node,ObjectNode> positions=new IdentityHashMap<>();Node defaults;String paragraphDefault,characterDefault,tableDefault;final String sourceSha,partName,partSha;
        Styles(XWPFDocument document,String sourceSha)throws Exception{
            this.sourceSha=sourceSha;String name=null,sha=null;
            if(document.getStyles()!=null)try(InputStream input=document.getStyles().getPackagePart().getInputStream()){
                byte[] bytes=org.springframework.util.StreamUtils.copyToByteArray(input);name=document.getStyles().getPackagePart().getPartName().getName().substring(1);sha=DocumentParseProbe.sha256(bytes);
                Node root=StylesDocument.Factory.parse(new java.io.ByteArrayInputStream(bytes)).getStyles().getDomNode();if(root.getNodeType()!=Node.ELEMENT_NODE){root=root.getFirstChild();while(root!=null&&root.getNodeType()!=Node.ELEMENT_NODE)root=root.getNextSibling();}
                defaults=child(child(child(root,"docDefaults"),"rPrDefault"),"rPr");
                if(root!=null)for(Node node=root.getFirstChild();node!=null;node=node.getNextSibling())if(word(node,"style")){String id=attribute(node,W,"styleId");if(id!=null){if(byId.containsKey(id))byId.put(id,null);else byId.put(id,node);}
                    if(Boolean.TRUE.equals(onOff(attribute(node,W,"default"),false))){String type=styleType(node);if("paragraph".equals(type))paragraphDefault=id;else if("character".equals(type))characterDefault=id;else if("table".equals(type))tableDefault=id;}}
            }partName=name;partSha=sha;
        }
        Format format(Node text,Map<Object,Node> sources){
            Format out=new Format();apply(defaults,out,false,"document_defaults",null,sources,true);
            Node run=ancestor(text,"r"),paragraph=ancestor(text,"p"),table=ancestor(text,"tbl");
            if(table!=null){String id=value(child(child(table,"tblPr"),"tblStyle"));applyStyle(id==null?tableDefault:id,"table",out,sources);}
            String paragraphId=value(child(child(paragraph,"pPr"),"pStyle"));applyStyle(paragraphId==null?paragraphDefault:paragraphId,"paragraph",out,sources);
            String characterId=value(child(child(run,"rPr"),"rStyle"));applyStyle(characterId==null?characterDefault:characterId,"character",out,sources);
            apply(child(run,"rPr"),out,false,"direct_run",null,sources,false);return out;
        }
        void applyStyle(String id,String type,Format out,Map<Object,Node> sources){
            if(id==null)return;List<Node> chain=new ArrayList<>();Set<String> visited=new HashSet<>();String current=id;
            while(current!=null){if(!visited.add(current)){out.unknown("style_inheritance_cycle");return;}Node style=byId.get(current);
                if(style==null||!type.equals(styleType(style))){out.unknown("missing_duplicate_or_wrong_type_style:"+current);return;}chain.add(style);current=value(child(style,"basedOn"));}
            Collections.reverse(chain);for(Node style:chain){apply(child(style,"rPr"),out,true,type+"_style",attribute(style,W,"styleId"),sources,true);
                if("table".equals(type))for(Node conditional=style.getFirstChild();conditional!=null;conditional=conditional.getNextSibling())if(word(conditional,"tblStylePr")){Node rpr=child(conditional,"rPr");if(child(rpr,"i")!=null||child(rpr,"vertAlign")!=null){out.unknown("conditional_table_style_effect_not_resolved");ObjectNode trace=out.trace.addObject();trace.put("layer","table_conditional_style");trace.set("sourcePosition",position(conditional));}}}
        }
        void apply(Node rpr,Format out,boolean toggle,String layer,String styleId,Map<Object,Node> sources,boolean styleSource){
            if(rpr==null)return;int italicCount=0,verticalCount=0;for(Node property=rpr.getFirstChild();property!=null;property=property.getNextSibling()){
                boolean italic=word(property,"i"),vertical=word(property,"vertAlign");if(!italic&&!vertical)continue;
                ObjectNode trace=out.trace.addObject();trace.put("layer",layer);trace.put("property",italic?"italic":"verticalAlign");trace.put("rawValue",value(property));if(styleId!=null)trace.put("styleId",styleId);
                if(styleSource)trace.set("sourcePosition",position(property));else sources.put(trace,property);
                if(italic){italicCount++;Boolean value=onOff(value(property),true);if(value==null||Boolean.TRUE.equals(value)||(!toggle&&!"document_defaults".equals(layer)))out.italicSeen=true;if(italicCount>1||value==null)out.italic=null;else if(!toggle)out.italic=value;else if(value&&out.italic!=null)out.italic=!out.italic;}
                else{verticalCount++;String value=value(property);if(!"document_defaults".equals(layer)||!"baseline".equals(value))out.verticalSeen=true;out.vertical=verticalCount==1&&Arrays.asList("baseline","superscript","subscript").contains(value)?value:null;}
            }
        }
        ObjectNode position(Node node){ObjectNode existing=positions.get(node);if(existing!=null)return existing;ObjectNode out=object();out.put("kind","docx_native");out.put("sourceSha256",sourceSha);out.put("partName",partName);out.put("partSha256",partSha);out.put("nodePath",path(node));positions.put(node,out);return out;}
    }
    private static String path(Node node){if(node==null||node.getNodeType()==Node.DOCUMENT_NODE)return "";if(node.getNodeType()!=Node.ELEMENT_NODE)return path(node.getParentNode());int ordinal=1;for(Node p=node.getPreviousSibling();p!=null;p=p.getPreviousSibling())if(p.getNodeType()==Node.ELEMENT_NODE&&Objects.equals(node.getNamespaceURI(),p.getNamespaceURI())&&Objects.equals(node.getLocalName(),p.getLocalName()))ordinal++;return path(node.getParentNode())+"/{"+node.getNamespaceURI()+"}"+node.getLocalName()+"["+ordinal+"]";}
    private static Boolean onOff(String raw,boolean absent){if(raw==null)return absent;if(Arrays.asList("1","true","on").contains(raw))return true;if(Arrays.asList("0","false","off").contains(raw))return false;return null;}
    private static boolean word(Node node,String name){return node!=null&&W.equals(node.getNamespaceURI())&&name.equals(node.getLocalName());}
    private static Node ancestor(Node node,String name){for(Node parent=node.getParentNode();parent!=null;parent=parent.getParentNode())if(word(parent,name))return parent;return null;}
    private static Node child(Node parent,String name){if(parent!=null)for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling())if(word(node,name))return node;return null;}
    private static String value(Node node){return attribute(node,W,"val");}
    private static String styleType(Node node){String type=attribute(node,W,"type");return type==null?"paragraph":type;}
    private static String attribute(Node node,String namespace,String name){if(node==null||node.getAttributes()==null)return null;Node value=namespace==null?node.getAttributes().getNamedItem(name):node.getAttributes().getNamedItemNS(namespace,name);return value==null?null:value.getNodeValue();}
    private static String text(Node node){StringBuilder out=new StringBuilder();for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()==Node.TEXT_NODE||child.getNodeType()==Node.CDATA_SECTION_NODE)out.append(child.getNodeValue());return out.toString();}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
}
