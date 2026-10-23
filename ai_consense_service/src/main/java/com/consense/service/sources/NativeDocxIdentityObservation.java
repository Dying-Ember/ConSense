package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import java.io.*;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** Identity evidence only: bounded native paragraphs; no full parser, OCR or rendered document. */
final class NativeDocxIdentityObservation {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_BLOCKS=1024,MAX_TEXT=131072,MAX_EVENTS=262144;
    private static final long MAX_PART_BYTES=64L*1024*1024;
    private NativeDocxIdentityObservation() {}
    static List<DocumentBlock> read(Path original,String expectedOriginalSha)throws Exception {
        try(ZipFile archive=new ZipFile(original.toFile())) {
            ZipEntry entry=null;int entries=0;Enumeration<? extends ZipEntry> names=archive.entries();
            while(names.hasMoreElements()) {ZipEntry candidate=names.nextElement();if(++entries>16384)throw new IOException("Identity ZIP entry budget exceeded");
                if("word/document.xml".equals(candidate.getName())){if(entry!=null)throw new IOException("Duplicate native document part");entry=candidate;}}
            if(entry==null||entry.isDirectory()||entry.getSize()>MAX_PART_BYTES)throw new IOException("Native document part unavailable or over budget");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");List<DocumentBlock> blocks;
            try(InputStream entryInput=archive.getInputStream(entry);InputStream input=new DigestInputStream(new FilterInputStream(entryInput){
                private long seen;private void count(int n)throws IOException{if(n>0&&(seen+=n)>MAX_PART_BYTES)throw new IOException("Native document part exceeds identity budget");}
                @Override public int read()throws IOException{int result=in.read();count(result<0?0:1);return result;}
                @Override public int read(byte[] b,int offset,int length)throws IOException{int result=in.read(b,offset,length);count(result);return result;}
            },digest)){
                blocks=paragraphs(new FilterInputStream(input){@Override public void close(){}});
                byte[] buffer=new byte[8192];while(input.read(buffer)!=-1){}
            }
            StringBuilder partSha=new StringBuilder();for(byte b:digest.digest())partSha.append(String.format("%02x",b&255));
            for(DocumentBlock block:blocks){com.fasterxml.jackson.databind.node.ObjectNode position=(com.fasterxml.jackson.databind.node.ObjectNode)block.getSourcePosition();
                position.put("kind","docx_native");position.put("sourceSha256",expectedOriginalSha);position.put("partName","word/document.xml");position.put("partSha256",partSha.toString());}
            return blocks;
        }
    }
    private static List<DocumentBlock> paragraphs(InputStream input)throws Exception {
        XMLInputFactory factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        XMLStreamReader xml=factory.createXMLStreamReader(input);List<DocumentBlock> blocks=new ArrayList<>();
        Deque<Map<String,Integer>> counts=new ArrayDeque<>();Deque<String> paths=new ArrayDeque<>();counts.push(new HashMap<>());
        StringBuilder text=null;String paragraphPath=null;int paragraphDepth=-1,textDepth=-1,deletedDepth=-1,hiddenRunDepth=-1,runDepth=-1,total=0,events=0,paragraphs=0;
        try{while(xml.hasNext()){
            if(++events>MAX_EVENTS)break;int event=xml.next();
            if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw new IOException("Native identity XML entities are unavailable");
            if(event==XMLStreamConstants.START_ELEMENT){
                if(paths.size()>=128)throw new IOException("Native identity XML nesting budget exceeded");
                String name="{"+xml.getNamespaceURI()+"}"+xml.getLocalName();Map<String,Integer> siblings=counts.peek();int ordinal=siblings.getOrDefault(name,0)+1;siblings.put(name,ordinal);
                String path=(paths.isEmpty()?"":paths.peek())+"/"+name+"["+ordinal+"]";paths.push(path);counts.push(new HashMap<>());int depth=paths.size();
                if(!W.equals(xml.getNamespaceURI()))continue;String local=xml.getLocalName();
                if("del".equals(local))deletedDepth=depth;
                if("p".equals(local)&&paragraphDepth<0){if(++paragraphs>MAX_BLOCKS)break;paragraphDepth=depth;paragraphPath=path;text=new StringBuilder();}
                if("r".equals(local)){runDepth=depth;hiddenRunDepth=-1;}
                if("vanish".equals(local)&&runDepth>0){String value=xml.getAttributeValue(W,"val");if(!Arrays.asList("0","false","off").contains(value))hiddenRunDepth=runDepth;}
                if(text!=null&&deletedDepth<0&&hiddenRunDepth<0){if("t".equals(local))textDepth=depth;else if("tab".equals(local))text.append('\t');else if("br".equals(local)||"cr".equals(local))text.append('\n');}
            }else if(event==XMLStreamConstants.CHARACTERS||event==XMLStreamConstants.CDATA){
                if(text!=null&&textDepth>0&&deletedDepth<0&&hiddenRunDepth<0){if((long)total+text.length()+xml.getTextLength()>MAX_TEXT)break;text.append(xml.getText());}
            }else if(event==XMLStreamConstants.END_ELEMENT){
                int depth=paths.size();if(depth==paragraphDepth){
                    if((long)total+text.length()>MAX_TEXT)break;total+=text.length();DocumentBlock block=new DocumentBlock();block.setKind("identity_native_paragraph");block.setText(text.toString());
                    block.setLocation("word/document.xml"+paragraphPath);com.fasterxml.jackson.databind.node.ObjectNode position=JsonUtils.mapper().createObjectNode();position.put("nodePath",paragraphPath);block.setSourcePosition(position);blocks.add(block);
                    text=null;paragraphPath=null;paragraphDepth=-1;
                }
                if(depth==textDepth)textDepth=-1;if(depth==deletedDepth)deletedDepth=-1;if(depth==runDepth){runDepth=-1;hiddenRunDepth=-1;}
                paths.pop();counts.pop();
            }
        }}finally{xml.close();}return blocks;
    }
}
