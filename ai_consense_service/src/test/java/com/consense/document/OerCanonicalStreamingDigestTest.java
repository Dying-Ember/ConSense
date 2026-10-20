package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.common.Utf8;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.lang.reflect.Method;
import java.math.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact old Writer-JSON/UTF8 hashes at the real inputSha/payloadSha seam; no child JVM or renderer. */
class OerCanonicalStreamingDigestTest {
    private static final String SOURCE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode out=JsonUtils.mapper().createObjectNode();List<String> keys=new ArrayList<>();value.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys)out.set(key,canonical(value.get(key)));return out;}if(value.isArray()){ArrayNode out=JsonUtils.mapper().createArrayNode();for(JsonNode member:value)out.add(canonical(member));return out;}return value;}
    private static String oldInput(String key,JsonNode blocks,JsonNode coverage){ObjectNode input=JsonUtils.mapper().createObjectNode();input.put("schema",OerTemplateEvidence.SCHEMA);input.put("analysisRevision",OerTemplateEvidence.ANALYSIS_REVISION);input.put("fileKey",key);input.put("sourceSha256",SOURCE);input.set("blocks",blocks==null?JsonUtils.mapper().nullNode():blocks.deepCopy());ObjectNode copy=coverage!=null&&coverage.isObject()?(ObjectNode)coverage.deepCopy():JsonUtils.mapper().createObjectNode();copy.remove("templateEvidence");input.set("coverage",copy);return Utf8.sha256(canonical(input).toString());}
    private static String oldPayload(JsonNode evidence){ObjectNode copy=evidence.deepCopy();if(copy.path("sourceBinding").isObject())((ObjectNode)copy.path("sourceBinding")).remove("generationSha256");return Utf8.sha256(canonical(copy).toString());}
    private static String input(String key,JsonNode blocks,JsonNode coverage)throws Exception {Method method=OerTemplateEvidence.class.getDeclaredMethod("inputSha",String.class,String.class,JsonNode.class,JsonNode.class);method.setAccessible(true);return (String)method.invoke(null,key,SOURCE,blocks,coverage);}
    private static String payload(JsonNode evidence)throws Exception {Method method=OerTemplateEvidence.class.getDeclaredMethod("payloadSha",JsonNode.class);method.setAccessible(true);return (String)method.invoke(null,evidence);}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static void equivalent(JsonNode blocks,JsonNode coverage)throws Exception {String beforeBlocks=blocks==null?null:blocks.toString(),beforeCoverage=coverage==null?null:coverage.toString();assertEquals(oldInput("NTT",blocks,coverage),input("NTT",blocks,coverage));ObjectNode evidence=object();evidence.set("members",blocks);evidence.set("coverage",coverage);evidence.putObject("sourceBinding").put("sourceSha256",SOURCE).put("generationSha256","ignored");String beforeEvidence=evidence.toString();assertEquals(oldPayload(evidence),payload(evidence));assertEquals(beforeBlocks,blocks==null?null:blocks.toString());assertEquals(beforeCoverage,coverage==null?null:coverage.toString());assertEquals(beforeEvidence,evidence.toString());}

    @Test void unorderedKeysArraysNumbersAndBinaryKeepTheExactOldWriterDigest()throws Exception {
        ObjectNode data=object();data.put("z",true);data.putNull("a");data.set("decimal",DecimalNode.valueOf(new BigDecimal("12345678901234567890.0012300")));data.set("scientificDecimal",DecimalNode.valueOf(new BigDecimal("1.23456789E+45")));data.set("scientificDouble",DoubleNode.valueOf(1.2345E-120));data.set("negativeZero",DoubleNode.valueOf(-0.0));data.set("largeInteger",BigIntegerNode.valueOf(new BigInteger("9876543210987654321098765432109876543210")));data.set("float",FloatNode.valueOf(1.25f));data.set("nan",DoubleNode.valueOf(Double.NaN));data.set("infinity",DoubleNode.valueOf(Double.POSITIVE_INFINITY));data.put("bytes",new byte[]{0,1,-1,34,92,10});
        ArrayNode array=data.putArray("array");array.add(3).add(2).add(1).addNull().add(false);array.addObject().put("z","tail").put("a","head");ObjectNode coverage=object();coverage.put("z","tail");coverage.put("a","head");equivalent(data,coverage);
        ObjectNode reordered=object();List<String> keys=new ArrayList<>();data.fieldNames().forEachRemaining(keys::add);Collections.reverse(keys);for(String key:keys)reordered.set(key,data.get(key));assertEquals(input("NTT",data,coverage),input("NTT",reordered,coverage));ArrayNode changed=array.deepCopy();changed.remove(0);ObjectNode different=data.deepCopy();different.set("array",changed);assertNotEquals(input("NTT",data,coverage),input("NTT",different,coverage));
    }

    @Test void chineseEmojiEscapesEveryUtf16UnitAndUnpairedSurrogatesKeepOldReplacementBytes()throws Exception {
        char[] units=new char[65536];for(int i=0;i<units.length;i++)units[i]=(char)i;
        for(String text:new String[]{"","ASCII\u0000\r\n\t\"\\/","中文合约\ud83d\ude00","\ud800","\udc00","\ud800\ud800","\udc00\ud800","a\ud800b\udc00c",new String(units)}){
            ObjectNode coverage=object();coverage.put("value",text);coverage.put("\ud800-key",text);coverage.put("\ud83d\ude00-key",text);equivalent(object().put("text",text),coverage);
        }
    }

    @Test void surrogatePairsAcrossJsonAndEncoderBufferBoundariesKeepOldBytes()throws Exception {
        for(int length:new int[]{3995,3996,3997,3998,3999,4000,4001,7995,7996,7997,7998,7999,8000,8188,8189,8190,8191,8192,8193,16383}){
            String boundary="a".repeat(length)+"\ud83d\ude00中\ud800z\udc00";ObjectNode coverage=object();coverage.put("long",boundary);coverage.put("highOnly","\ud800");coverage.put("lowOnly","\udc00");equivalent(object().put("long",boundary),coverage);
        }
    }

    @Test void exclusionsAffectOnlyTheirExactDirectPathsAndNeverMutateAliasedInputs()throws Exception {
        ObjectNode coverage=object();coverage.put("templateEvidence","excluded-direct-only");coverage.putObject("nested").put("templateEvidence","must-keep");coverage.putObject("sourceBinding").put("generationSha256","must-keep");ObjectNode blocks=object();blocks.set("sharedCoverage",coverage);blocks.put("templateEvidence","keep-in-blocks");String beforeBlocks=blocks.toString(),beforeCoverage=coverage.toString();
        String baseline=input("NTT",blocks,coverage);assertEquals(oldInput("NTT",blocks,coverage),baseline);assertEquals(beforeBlocks,blocks.toString());assertEquals(beforeCoverage,coverage.toString());ObjectNode changed=coverage.deepCopy();changed.put("templateEvidence","changed-ignored");assertEquals(baseline,input("NTT",blocks,changed));((ObjectNode)changed.get("nested")).put("templateEvidence","changed-retained");assertNotEquals(baseline,input("NTT",blocks,changed));
        ObjectNode binding=object();binding.put("generationSha256","direct-ignore");binding.putObject("nested").put("generationSha256","nested-keep");ObjectNode evidence=object();evidence.set("sourceBinding",binding);evidence.set("aliasedOtherBinding",binding);evidence.put("generationSha256","root-keep");String before=evidence.toString();String old=oldPayload(evidence);assertEquals(old,payload(evidence));assertEquals(before,evidence.toString());ObjectNode isolated=evidence.deepCopy();((ObjectNode)isolated.get("sourceBinding")).put("generationSha256","direct-change");assertEquals(old,payload(isolated));((ObjectNode)isolated.get("aliasedOtherBinding")).put("generationSha256","retained-alias-change");assertNotEquals(old,payload(isolated));
        equivalent(null,null);
        for(JsonNode nonobject:new JsonNode[]{JsonUtils.mapper().nullNode(),JsonUtils.mapper().createArrayNode().add(object().put("templateEvidence","array-keep")),TextNode.valueOf("text")}){equivalent(null,nonobject);ObjectNode other=object();other.set("sourceBinding",nonobject);other.putObject("nested").put("generationSha256","nested-retain");String original=other.toString();assertEquals(oldPayload(other),payload(other));assertEquals(original,other.toString());}
    }

    @Test void largeSharedTextLeavesMatchTheOldHashWithoutChangingEitherInput()throws Exception {
        String text="a".repeat(6_000_000)+"中\ud83d\ude00\ud800";TextNode shared=TextNode.valueOf(text);ObjectNode blocks=object();blocks.set("z",shared);blocks.set("a",shared);ObjectNode coverage=object();coverage.put("templateEvidence","ignored");coverage.set("text",shared);String actual=input("SCC",blocks,coverage);assertEquals(oldInput("SCC",blocks,coverage),actual);assertSame(shared,blocks.get("a"));assertSame(shared,blocks.get("z"));assertSame(shared,coverage.get("text"));assertEquals(text,coverage.get("text").asText());
    }

    @Test void independentRepeatedTreeExpectedDigestMatchesTheSmallLegacyReference()throws Exception {
        ObjectNode coverage=object().put("templateEvidence","excluded");assertEquals(oldInput("SCC",repeatedTree(37,3),coverage),repeatedExpected(37,3));assertEquals(repeatedExpected(37,3),input("SCC",repeatedTree(37,3),coverage));
    }

    private static ObjectNode repeatedTree(int length,int count){char[] chars=new char[length+4];Arrays.fill(chars,'a');chars[length]='中';chars[length+1]='\ud83d';chars[length+2]='\ude00';chars[length+3]='\ud800';TextNode shared=TextNode.valueOf(new String(chars));ObjectNode blocks=object();for(int i=0;i<count;i++)blocks.set(String.format(Locale.ROOT,"k%02d",i),shared);return blocks;}
    private static String repeatedExpected(int length,int count)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");Utf8.update(digest,"{\"analysisRevision\":"+JsonUtils.write(OerTemplateEvidence.ANALYSIS_REVISION)+",\"blocks\":{");byte[] ascii=new byte[8192];Arrays.fill(ascii,(byte)'a');
        for(int i=0;i<count;i++){if(i>0)Utf8.update(digest,",");Utf8.update(digest,JsonUtils.write(String.format(Locale.ROOT,"k%02d",i))+":\"");for(int left=length;left>0;){int n=Math.min(left,ascii.length);digest.update(ascii,0,n);left-=n;}Utf8.update(digest,"中\ud83d\ude00?\"");}
        Utf8.update(digest,"},\"coverage\":{},\"fileKey\":\"SCC\",\"schema\":"+JsonUtils.write(OerTemplateEvidence.SCHEMA)+",\"sourceSha256\":\""+SOURCE+"\"}");StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();
    }

}
