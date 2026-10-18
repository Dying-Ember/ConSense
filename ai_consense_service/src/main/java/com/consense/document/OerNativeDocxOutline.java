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
import java.util.function.Function;

/** Source-defined outline, isolated by body/cell/textbox; it does not assign legal applicability. */
final class OerNativeDocxOutline {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final Map<String,Node> styles=new HashMap<>();
    private final Map<Node,ObjectNode> paragraphProperties=new IdentityHashMap<>();
    private final Map<Node,List<DocumentBlock>> represented=new IdentityHashMap<>();
    private final Map<Node,String> stylePaths=new IdentityHashMap<>();
    private final Function<Node,ObjectNode> position;
    private final Function<Node,JsonNode> paragraphText;
    private final ArrayNode headings,paragraphs;
    private final DocxNumberingResolver numbering;
    private final String sourceSha;
    private String stylesPartName,stylesPartSha;
    private Node defaults;
    private String defaultStyle;

    private OerNativeDocxOutline(XWPFDocument document,Function<Node,ObjectNode> position,Function<Node,JsonNode> paragraphText,ObjectNode inventory,DocxNumberingResolver numbering)throws Exception{
        this.position=position;this.paragraphText=paragraphText;headings=inventory.putArray("headings");paragraphs=inventory.putArray("paragraphs");
        this.numbering=numbering;sourceSha=position.apply(document.getDocument().getBody().getDomNode()).path("sourceSha256").asText();
        if(document.getStyles()!=null)try(InputStream input=document.getStyles().getPackagePart().getInputStream()){
            byte[] bytes=org.springframework.util.StreamUtils.copyToByteArray(input);stylesPartName=document.getStyles().getPackagePart().getPartName().getName().substring(1);stylesPartSha=DocumentParseProbe.sha256(bytes);
            Node root=StylesDocument.Factory.parse(new java.io.ByteArrayInputStream(bytes)).getStyles().getDomNode();
            if(root.getNodeType()!=Node.ELEMENT_NODE){root=root.getFirstChild();while(root!=null&&root.getNodeType()!=Node.ELEMENT_NODE)root=root.getNextSibling();}
            defaults=child(child(child(root,"docDefaults"),"pPrDefault"),"pPr");
            for(Node style=root.getFirstChild();style!=null;style=style.getNextSibling())if(word(style,"style")){
                String id=attribute(style,"styleId");if(id!=null)styles.put(id,style);
                if("paragraph".equals(attribute(style,"type"))&&Arrays.asList("1","true","on").contains(attribute(style,"default")))defaultStyle=id;
            }
        }
    }

    static ObjectNode observe(XWPFDocument document,List<DocumentBlock> blocks,Map<?,Node> sourceNodes,Function<Node,ObjectNode> position)throws Exception{
        return observe(document,blocks,sourceNodes,position,null);
    }

    static ObjectNode observe(XWPFDocument document,List<DocumentBlock> blocks,Map<?,Node> sourceNodes,Function<Node,ObjectNode> position,Function<Node,JsonNode> paragraphText)throws Exception{
        return observe(document,blocks,sourceNodes,position,paragraphText,null);
    }

    static ObjectNode observe(XWPFDocument document,List<DocumentBlock> blocks,Map<?,Node> sourceNodes,Function<Node,ObjectNode> position,Function<Node,JsonNode> paragraphText,DocxNumberingResolver numbering)throws Exception{
        ObjectNode inventory=object();inventory.put("version","docx-native-outline-v1");inventory.put("status","observed_source_defined_outline");
        inventory.put("scopeMeaning","OOXML outline and physical source membership; page layout and legal applicability remain separate");
        OerNativeDocxOutline observer=new OerNativeDocxOutline(document,position,paragraphText,inventory,numbering);
        for(DocumentBlock block:blocks)if(block.getLocation()!=null&&block.getLocation().startsWith("body/")){
            Node node=sourceNodes.get(block);if(node!=null)observer.represented.computeIfAbsent(node,k->new ArrayList<>()).add(block);
        }
        observer.scope(document.getDocument().getBody().getDomNode());return inventory;
    }

    private void scope(Node container){
        List<Node> flow=new ArrayList<>();flow(container,flow);List<ObjectNode> active=new ArrayList<>();
        for(Node member:flow){
            ObjectNode properties=word(member,"p")?properties(member):null;
            if(properties!=null&&!represented.containsKey(member))for(Node ancestor=member.getParentNode();ancestor!=null;ancestor=ancestor.getParentNode()){
                List<DocumentBlock> enclosing=represented.get(ancestor);if(enclosing==null)continue;
                for(DocumentBlock block:enclosing){ObjectNode context=context(block);ArrayNode records=context.has("nativeDocxParagraphs")?(ArrayNode)context.get("nativeDocxParagraphs"):context.putArray("nativeDocxParagraphs");records.add(properties.deepCopy());}break;
            }
            if(properties!=null&&properties.has("outlineLevel")&&properties.path("outlineLevel").asInt()<9&&"resolved".equals(properties.path("status").asText())){
                int level=properties.path("outlineLevel").asInt();
                while(!active.isEmpty()&&active.get(active.size()-1).path("outlineLevel").asInt()>=level)close(active.remove(active.size()-1),"next_native_outline_heading",member);
                ObjectNode heading=object();heading.put("ordinalZeroBased",headings.size());heading.put("outlineLevel",level);
                if(active.isEmpty())heading.putNull("parentOrdinalZeroBased");else heading.put("parentOrdinalZeroBased",active.get(active.size()-1).path("ordinalZeroBased").asInt());
                heading.set("sourcePosition",position.apply(member));heading.set("containerPosition",position.apply(container));heading.set("properties",properties.deepCopy());
                if(paragraphText!=null)heading.set("textObservation",textObservation(member));
                heading.putArray("nativeMemberPaths");heading.putArray("retainedBlockIds");headings.add(heading);active.add(heading);
                for(Node ancestor=member.getParentNode();ancestor!=null;ancestor=ancestor.getParentNode()){
                    List<DocumentBlock> enclosing=represented.get(ancestor);if(enclosing==null)continue;
                    for(DocumentBlock block:enclosing){ObjectNode context=context(block),reference=context.has("nativeDocxOutline")?(ObjectNode)context.get("nativeDocxOutline"):context.putObject("nativeDocxOutline");
                        ArrayNode contained=reference.has("containedHeadingOrdinals")?(ArrayNode)reference.get("containedHeadingOrdinals"):reference.putArray("containedHeadingOrdinals");contained.add(heading.path("ordinalZeroBased").asInt());}
                    break;
                }
            }
            List<DocumentBlock> blocks=new ArrayList<>();represented(member,blocks);
            for(ObjectNode heading:active){
                ((ArrayNode)heading.get("nativeMemberPaths")).add(position.apply(member).path("nodePath").asText());
                for(DocumentBlock block:blocks)((ArrayNode)heading.get("retainedBlockIds")).add(block.getId());
            }
            for(DocumentBlock block:blocks){
                ObjectNode context=context(block);
                if(properties!=null&&represented.getOrDefault(member,Collections.emptyList()).contains(block))context.set("nativeDocxParagraph",properties.deepCopy());
                ArrayNode refs=context.putObject("nativeDocxOutline").putArray("ancestorOrdinals");
                for(ObjectNode heading:active)refs.add(heading.path("ordinalZeroBased").asInt());
            }
            for(Node nested:nestedScopes(member))scope(nested);
        }
        for(ObjectNode heading:active)close(heading,"native_container_end",container);
    }

    private void close(ObjectNode heading,String kind,Node boundary){
        heading.putObject("endBoundary").put("kind",kind).set("sourcePosition",position.apply(boundary));
        if(paragraphText!=null&&"next_native_outline_heading".equals(kind))heading.set("endBoundaryTextObservation",textObservation(boundary));
    }

    private ObjectNode textObservation(Node paragraph){
        ObjectNode out=object();out.set("sourcePosition",position.apply(paragraph));JsonNode captured=paragraphText.apply(paragraph);
        for(String key:Arrays.asList("text","originalText","deletedText","strikeText"))if(captured==null||!captured.path(key).isTextual()){
            out.put("status","unknown");out.put("reason","native_paragraph_channels_not_captured");return out;
        }
        out.put("status","observed_parser_paragraph_channels");
        for(String key:Arrays.asList("text","originalText","deletedText","strikeText"))out.set(key,captured.get(key));
        out.put("meaning","Native parser paragraph channels only; layout, editing adoption and legal applicability remain separate");return out;
    }

    private ObjectNode properties(Node paragraph){
        ObjectNode prior=paragraphProperties.get(paragraph);if(prior!=null)return prior;
        ObjectNode out=object();out.set("sourcePosition",position.apply(paragraph));
        Node ppr=child(paragraph,"pPr"),outline=child(ppr,"outlineLvl");String styleId=value(child(ppr,"pStyle"));
        out.put("paragraphStyleId",styleId);ArrayNode chain=out.putArray("styleChain");Set<String> visited=new HashSet<>();
        String current=styleId==null?defaultStyle:styleId,basis=outline==null?null:"paragraph";
        while(current!=null){
            if(!visited.add(current)){out.put("styleChainStatus","style_inheritance_cycle");if(outline==null)out.put("status","style_inheritance_cycle");break;}
            chain.add(current);Node style=styles.get(current);
            if(style==null){out.put("styleChainStatus","missing_paragraph_style");if(outline==null)out.put("status","missing_paragraph_style");break;}
            if(outline==null){Node inherited=child(child(style,"pPr"),"outlineLvl");if(inherited!=null){outline=inherited;basis="paragraph_style";out.put("outlineStyleId",current);}}
            current=value(child(style,"basedOn"));
        }
        if(outline==null&&!out.has("status")){outline=child(defaults,"outlineLvl");if(outline!=null)basis="document_defaults";}
        if(outline!=null){
            String raw=value(outline);out.put("outlineRaw",raw);out.put("outlineBasis",basis);
            try{int level=Integer.parseInt(raw);if(level<0||level>9)throw new NumberFormatException();out.put("outlineLevel",level);if(!out.has("status"))out.put("status","resolved");}
            catch(NumberFormatException invalid){out.put("status","invalid_outline_level");}
        }else if(!out.has("status"))out.put("status","outline_not_declared");
        out.set("layoutEvidence",layoutEvidence(paragraph,ppr,out));paragraphProperties.put(paragraph,out);paragraphs.add(out);return out;
    }

    private ObjectNode layoutEvidence(Node paragraph,Node direct,JsonNode properties){
        ObjectNode out=object();out.put("status","source_declarations_only");out.put("meaning","Raw source paragraph layout declarations; character units and twips remain distinct, and page geometry is not resolved");ArrayNode layers=out.putArray("layers");
        Function<Node,ObjectNode> stylePosition=node->layoutPosition(node,sourceSha,stylesPartName,stylesPartSha,stylePaths);
        layoutLayer(layers,defaults,"document_defaults",null,stylePosition);
        List<String> chain=new ArrayList<>();for(JsonNode id:properties.path("styleChain"))chain.add(id.asText());Collections.reverse(chain);
        for(String id:chain)layoutLayer(layers,child(styles.get(id),"pPr"),"paragraph_style",id,stylePosition);
        if(numbering!=null){ObjectNode observed=numbering.layoutEvidence(paragraph,sourceSha);if(observed!=null){out.set("numbering",observed.path("numbering"));for(JsonNode layer:observed.path("layers"))layers.add(layer);}}
        layoutLayer(layers,direct,"direct_paragraph",null,position);
        ObjectNode effective=out.putObject("effective");Map<String,List<JsonNode>> candidates=new LinkedHashMap<>();
        int layerIndex=0;for(JsonNode layer:layers){int index=layerIndex++;for(JsonNode declaration:layer.path("declarations")){
            String name=declaration.path("name").asText();if("tabs".equals(name)){effective.putObject("tabs").put("status","unknown").put("reason","tab_position_merge_and_clear_not_resolved");continue;}
            Iterator<Map.Entry<String,JsonNode>> attrs=declaration.path("attributes").fields();while(attrs.hasNext()){Map.Entry<String,JsonNode> attr=attrs.next();String key=name+"\n"+attr.getKey();ObjectNode candidate=object();candidate.put("value",attr.getValue().asText());candidate.put("kind",layer.path("kind").asText());candidate.put("layerIndex",index);candidate.set("sourcePosition",declaration.path("sourcePosition"));candidates.computeIfAbsent(key,k->new ArrayList<>()).add(candidate);}
        }}
        for(Map.Entry<String,List<JsonNode>> entry:candidates.entrySet()){
            String[] key=entry.getKey().split("\n",2);ObjectNode property=effective.has(key[0])?(ObjectNode)effective.get(key[0]):effective.putObject(key[0]);List<JsonNode> declarations=entry.getValue();JsonNode directWinner=null,styleWinner=null,numberWinner=null;
            for(JsonNode candidate:declarations){String kind=candidate.path("kind").asText();if("direct_paragraph".equals(kind))directWinner=candidate;else if(kind.startsWith("numbering_"))numberWinner=candidate;else styleWinner=candidate;}
            ObjectNode winner=property.putObject(key[1]);String reason=null;
            if(repeatedLayoutAttribute(declarations,directWinner))reason="duplicate_layout_property_declarations";
            else if(directWinner!=null)winner.setAll((ObjectNode)directWinner);
            else if(properties.has("styleChainStatus"))reason=properties.path("styleChainStatus").asText();
            else if(repeatedLayoutAttribute(declarations,styleWinner)||repeatedLayoutAttribute(declarations,numberWinner))reason="duplicate_layout_property_declarations";
            else if(styleWinner!=null&&numberWinner!=null&&!styleWinner.path("value").equals(numberWinner.path("value")))reason="numbering_and_paragraph_style_precedence_not_verified";
            else if(numberWinner!=null)winner.setAll((ObjectNode)numberWinner);else if(styleWinner!=null)winner.setAll((ObjectNode)styleWinner);
            winner.remove("layerIndex");winner.put("status",reason==null?"resolved":"unknown");if(reason!=null)winner.put("reason",reason);
        }
        return out;
    }

    private static boolean repeatedLayoutAttribute(List<JsonNode> declarations,JsonNode selected){if(selected==null)return false;int count=0;for(JsonNode candidate:declarations)if(candidate.path("layerIndex").equals(selected.path("layerIndex"))&&++count>1)return true;return false;}

    static ObjectNode layoutPosition(Node node,String sourceSha,String part,String partSha,Map<Node,String> paths){ObjectNode out=object();out.put("kind","docx_native");out.put("sourceSha256",sourceSha);out.put("partName",part);out.put("partSha256",partSha);out.put("nodePath",DocumentParser.nativeWordPath(node,paths));return out;}
    static void layoutLayer(ArrayNode layers,Node ppr,String kind,String styleId,Function<Node,ObjectNode> position){
        if(ppr==null)return;ArrayNode declarations=JsonUtils.mapper().createArrayNode();
        for(Node node=ppr.getFirstChild();node!=null;node=node.getNextSibling())if(word(node,"ind")||word(node,"tabs")||word(node,"jc")||word(node,"bidi")||word(node,"textDirection")){
            ObjectNode declaration=declarations.addObject();declaration.put("name",node.getLocalName());declaration.set("sourcePosition",position.apply(node));declaration.set("attributes",layoutAttributes(node));
            if(word(node,"tabs")){ArrayNode tabs=declaration.putArray("tabs");for(Node tab=node.getFirstChild();tab!=null;tab=tab.getNextSibling())if(word(tab,"tab")){ObjectNode entry=tabs.addObject();entry.set("sourcePosition",position.apply(tab));entry.set("attributes",layoutAttributes(tab));}}
        }
        if(declarations.isEmpty())return;ObjectNode layer=layers.addObject();layer.put("kind",kind);if(styleId!=null)layer.put("styleId",styleId);layer.set("sourcePosition",position.apply(ppr));layer.set("declarations",declarations);
    }
    private static ObjectNode layoutAttributes(Node node){ObjectNode out=object();if(node.getAttributes()!=null)for(int n=0;n<node.getAttributes().getLength();n++){Node attr=node.getAttributes().item(n);if(W.equals(attr.getNamespaceURI()))out.put("{"+W+"}"+attr.getLocalName(),attr.getNodeValue());}return out;}

    private void represented(Node node,List<DocumentBlock> out){
        List<DocumentBlock> exact=represented.get(node);if(exact!=null){out.addAll(exact);return;}
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()==Node.ELEMENT_NODE)represented(child,out);
    }
    private static List<Node> nestedScopes(Node parent){List<Node> out=new ArrayList<>();nestedScopes(parent,out);return out;}
    private static void nestedScopes(Node node,List<Node> out){
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()==Node.ELEMENT_NODE){
            if(word(child,"tc")||word(child,"txbxContent"))out.add(child);else nestedScopes(child,out);
        }
    }
    private static void flow(Node parent,List<Node> out){
        for(Node child=parent.getFirstChild();child!=null;child=child.getNextSibling())if(child.getNodeType()==Node.ELEMENT_NODE){
            if(word(child,"p")||word(child,"tbl"))out.add(child);
            else if(word(child,"sdt")){Node content=child(child,"sdtContent");if(content!=null)flow(content,out);}
            else if(word(child,"customXml"))flow(child,out);
        }
    }
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static ObjectNode context(DocumentBlock block){ObjectNode value=block.getReadingContext() instanceof ObjectNode?(ObjectNode)block.getReadingContext():object();block.setReadingContext(value);return value;}
    private static boolean word(Node node,String name){return node!=null&&W.equals(node.getNamespaceURI())&&name.equals(node.getLocalName());}
    private static Node child(Node parent,String name){if(parent!=null)for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(word(n,name))return n;return null;}
    private static String attribute(Node node,String name){Node value=node==null||node.getAttributes()==null?null:node.getAttributes().getNamedItemNS(W,name);return value==null?null:value.getNodeValue();}
    private static String value(Node node){return attribute(node,"val");}
}
