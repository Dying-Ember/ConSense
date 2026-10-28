package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Request-owned digests retain scalar identities, never a complete source or JSON graph. */
final class NormalPointerStream {
    private NormalPointerStream(){}
    static final class SourceProof {
        final String parsed,coverage,graph,graphCoverage;
        final String[] fields;
        SourceProof(String parsed,String coverage,String graph,String graphCoverage,String[] fields){this.parsed=parsed;this.coverage=coverage;this.graph=graph;this.graphCoverage=graphCoverage;this.fields=fields;}
        String business(){return SourceContentDigests.combined(fields[0],fields[1],fields[2],fields[3]);}
    }
    static SourceProof source(Reader text,Reader structure,Reader coverage,Reader candidate)throws IOException {
        MessageDigest raw=sha(),normalized=sha();Writer rawText=writer(raw),normalizedText=writer(normalized);
        Field textField=new Field(text,rawText,normalizedText);drain(textField.reader);textField.finish();rawText.write('\n');normalizedText.write('\n');
        Field structureField=new Field(structure,rawText);boolean enriched=false;
        if(structureField.reader!=null)try(JsonParser parser=parser(structureField.reader);JsonGenerator generator=JsonUtils.mapper().getFactory().createGenerator(normalizedText)){
            if(parser.nextToken()!=JsonToken.START_ARRAY)throw new IOException("Source structure must remain an array");
            generator.writeStartArray();
            while(parser.nextToken()!=JsonToken.END_ARRAY){
                JsonNode block=JsonUtils.mapper().readTree(parser);
                if(block==null)throw new IOException("Missing source structure member");
                if(block.isObject()&&block.has("paragraphStyleName")){((ObjectNode)block).remove("paragraphStyleName");enriched=true;}
                generator.writeTree(block);
            }
            generator.writeEndArray();if(parser.nextToken()!=null)throw new IOException("Trailing source structure");
        }
        structureField.finish();rawText.close();normalizedText.close();
        MessageDigest coverageRaw=sha();Writer coverageText=writer(coverageRaw);Field coverageField=new Field(coverage,coverageText);
        drain(coverageField.reader);coverageField.finish();coverageText.close();
        Field candidateField=new Field(candidate);Map<String,String> scalars=new HashMap<>();String graph;
        if(candidateField.reader==null)throw new IOException("Missing normal source generation");
        try(JsonParser parser=parser(candidateField.reader)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Missing normal source generation object");
            graph=identity(parser,scalars,0);if(parser.nextToken()!=null)throw new IOException("Trailing source generation");
        }
        candidateField.finish();
        return new SourceProof(hex(enriched?normalized:raw),hex(coverageRaw),graph,scalars.get("coverageSha256"),
                new String[]{textField.digest(),structureField.digest(),coverageField.digest(),candidateField.digest()});
    }
    static String identity(JsonParser parser)throws IOException{return identity(parser,null,0);}
    private static String identity(JsonParser parser,Map<String,String> scalars,int depth)throws IOException {
        if(depth>128)throw new IOException("JSON nesting exceeds the source proof budget");
        JsonToken token=parser.currentToken();MessageDigest digest=sha();Utf8.update(digest,token.name());digest.update((byte)'\n');
        if(token==JsonToken.START_OBJECT){
            Map<String,String> members=new TreeMap<>();
            while(parser.nextToken()!=JsonToken.END_OBJECT){
                if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new IOException("Expected JSON field");
                String key=parser.currentName();parser.nextToken();
                if(depth==0&&scalars!=null&&parser.currentToken()==JsonToken.VALUE_STRING)scalars.put(key,parser.getText());
                String child=identity(parser,null,depth+1);if(members.putIfAbsent(key,child)!=null)throw new IOException("Duplicate JSON field");
            }
            for(Map.Entry<String,String> member:members.entrySet()){Utf8.update(digest,Utf8.sha256(member.getKey()));Utf8.update(digest,member.getValue());}
        }else if(token==JsonToken.START_ARRAY){
            while(parser.nextToken()!=JsonToken.END_ARRAY)Utf8.update(digest,identity(parser,null,depth+1));
        }else if(token==JsonToken.VALUE_STRING)Utf8.update(digest,parser.getText());
        else if(token!=null&&token.isNumeric()){
            // Match the existing JsonNode content comparison, including IntNode/LongNode equivalence.
            JsonNode number=JsonUtils.mapper().readTree(parser);Utf8.update(digest,number.isIntegralNumber()?number.bigIntegerValue().toString():number.toString());
        }else if(token==null||!token.isScalarValue())throw new IOException("Invalid JSON value");
        return hex(digest);
    }
    static JsonParser parser(Reader reader)throws IOException{return JsonUtils.mapper().getFactory().createParser(reader).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    private static void drain(Reader reader)throws IOException {if(reader==null)return;try(Reader source=reader){char[] buffer=new char[8192];while(source.read(buffer)!=-1){}}}
    private static final class Field {
        final Reader reader;final MessageDigest hash=sha();final Writer output;
        Field(Reader source,Writer... additional)throws IOException {
            output=writer(hash);output.write(source==null?"source-null-v1":"source-string-v1\n");
            reader=source==null?null:new FilterReader(source){
                @Override public int read(char[] buffer,int offset,int length)throws IOException {int count=in.read(buffer,offset,length);if(count>0){output.write(buffer,offset,count);for(Writer target:additional)target.write(buffer,offset,count);}return count;}
                @Override public int read()throws IOException {int value=in.read();if(value>=0){output.write(value);for(Writer target:additional)target.write(value);}return value;}
            };
        }
        void finish()throws IOException{output.close();}
        String digest(){return hex(hash);}
    }
    private static Writer writer(MessageDigest digest){return new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(),digest),StandardCharsets.UTF_8);}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String hex(MessageDigest digest){return java.util.HexFormat.of().formatHex(digest.digest());}
}
