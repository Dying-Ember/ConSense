package com.consense.service.sources;

import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.math.*;
import java.util.*;

/** Sort object keys without changing Python-produced number tokens or unknown fields. */
final class CanonicalJson {
    private CanonicalJson(){}
    private static final JsonFactory FACTORY=new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final class NormalReceipt {
        final String projection;
        final com.fasterxml.jackson.databind.JsonNode header;
        NormalReceipt(String projection,com.fasterxml.jackson.databind.JsonNode header){this.projection=projection;this.header=header;}
    }
    /** Validate every wire token but retain only the projection span and receipt checks. */
    static NormalReceipt normalObservedReceipt(byte[] bytes){
        CanonicalEvidenceBackend.Reply.requireUtf8(bytes);
        try{
            // The String receipt path accepted UTF-8 JSON only, without a BOM. Do not let byte-parser autodetection widen it.
            int first=0;while(first<bytes.length&&(bytes[first]==' '||bytes[first]=='\n'||bytes[first]=='\r'||bytes[first]=='\t'))first++;
            if(first==bytes.length||bytes[first]!='{')throw new IOException("Expected UTF-8 object");
            for(int n=0;n<Math.min(4,bytes.length);n++)if(bytes[n]==0)throw new IOException("Expected UTF-8 object");
            com.fasterxml.jackson.databind.node.ObjectNode header=com.consense.common.JsonUtils.mapper().createObjectNode();
            Set<String> retained=new HashSet<>(Arrays.asList("projectionSha256","chunks","immutable","qualityVerifiedByRegistry","sourceQualityChangedByRegistry","responseFormat"));
            int start=-1,end=-1;
            try(JsonParser parser=FACTORY.createParser(bytes)){
                if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Expected object");
                JsonToken token;
                while((token=parser.nextToken())!=JsonToken.END_OBJECT){
                    if(token!=JsonToken.FIELD_NAME)throw new IOException("Expected receipt field");
                    String name=parser.currentName();token=parser.nextToken();
                    if("projection".equals(name)){
                        if(token!=JsonToken.START_OBJECT)throw new IOException("Expected projection object");
                        start=Math.toIntExact(parser.getTokenLocation().getByteOffset());parser.skipChildren();
                        end=Math.toIntExact(parser.getCurrentLocation().getByteOffset());
                    }else if(retained.contains(name)){
                        if(token!=null&&token.isScalarValue())header.set(name,com.consense.common.JsonUtils.mapper().readTree(parser));
                        else{skipValue(parser);header.putNull(name);}
                    }else skipValue(parser);
                }
                if(start<0||end<=start||end>bytes.length||parser.nextToken()!=null)throw new IOException("Missing projection or trailing JSON");
            }
            return new NormalReceipt(new String(bytes,start,end-start,StandardCharsets.UTF_8),header);
        }catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static String hash(String json,String omitRootField) {
        try(JsonParser parser=FACTORY.createParser(json)) {
            parser.nextToken();CanonicalValue value=readValue(parser,omitRootField);
            if(parser.nextToken()!=null)throw new IOException("Trailing JSON");
            return digest(value);
        }catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static String fieldHash(String json,String name) {
        try{return digest(fieldValue(json,name));}
        catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    /** Extract a canonical subtree from a noncanonical HTTP receipt without rewriting number tokens. */
    static String field(String json,String name) {
        try{CanonicalValue value=fieldValue(json,name);LengthWriter length=new LengthWriter();value.write(length);
            StringWriter out=new StringWriter(Math.toIntExact(length.length));value.write(out);return out.toString();}
        catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    private static CanonicalValue fieldValue(String json,String name)throws IOException {
        try(JsonParser p=FACTORY.createParser(json)){
            if(p.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Expected object");
            CanonicalValue found=null;
            while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.getCurrentName();p.nextToken();if(key.equals(name))found=readValue(p,null);else skipValue(p);}
            if(found==null||p.nextToken()!=null)throw new IOException("Missing field or trailing JSON");
            return found;
        }
    }
    private interface CanonicalValue {void write(Writer out)throws IOException;}
    private static final class LengthWriter extends Writer {
        private long length;
        @Override public void write(int value){length++;}
        @Override public void write(char[] value,int offset,int count){length+=count;}
        @Override public void write(String value,int offset,int count){length+=count;}
        @Override public void flush(){}
        @Override public void close(){}
    }
    /** Retain sortable values, never a canonical string for every enclosing object. */
    private static CanonicalValue readValue(JsonParser p,String omit)throws IOException {
        JsonToken token=p.currentToken();
        if(token==JsonToken.START_OBJECT){
            Map<String,CanonicalValue> fields=new TreeMap<>(CanonicalJson::compareCodepoints);
            while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.getCurrentName();p.nextToken();if(key.equals(omit))skipValue(p);else fields.put(key,readValue(p,null));}
            return out->{out.write('{');boolean first=true;for(Map.Entry<String,CanonicalValue> row:fields.entrySet()){if(!first)out.write(',');first=false;writeQuoted(out,row.getKey());out.write(':');row.getValue().write(out);}out.write('}');};
        }
        if(token==JsonToken.START_ARRAY){
            List<CanonicalValue> values=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY)values.add(readValue(p,null));
            return out->{out.write('[');boolean first=true;for(CanonicalValue value:values){if(!first)out.write(',');first=false;value.write(out);}out.write(']');};
        }
        if(token==JsonToken.VALUE_STRING){String value=p.getText();return out->writeQuoted(out,value);}
        if(token==JsonToken.VALUE_NUMBER_INT||token==JsonToken.VALUE_NUMBER_FLOAT){String value=p.getText();return out->out.write(value);}
        if(token==JsonToken.VALUE_TRUE)return out->out.write("true");
        if(token==JsonToken.VALUE_FALSE)return out->out.write("false");
        if(token==JsonToken.VALUE_NULL)return out->out.write("null");
        throw new IOException("Invalid JSON token");
    }
    private static void skipValue(JsonParser p)throws IOException {
        JsonToken token=p.currentToken();
        if(token==JsonToken.START_OBJECT||token==JsonToken.START_ARRAY){p.skipChildren();return;}
        if(token==JsonToken.VALUE_STRING||token==JsonToken.VALUE_NUMBER_INT||token==JsonToken.VALUE_NUMBER_FLOAT
                ||token==JsonToken.VALUE_TRUE||token==JsonToken.VALUE_FALSE||token==JsonToken.VALUE_NULL)return;
        throw new IOException("Invalid JSON token");
    }
    private static String digest(CanonicalValue value)throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            OutputStream discard=new OutputStream(){@Override public void write(int value){}@Override public void write(byte[] bytes,int offset,int length){}};
            // Buffer characters too: OutputStreamWriter.write(String) otherwise copies an entire scalar.
            try(Writer out=new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(discard,digest),StandardCharsets.UTF_8))){value.write(out);}
            StringBuilder hex=new StringBuilder(64);for(byte b:digest.digest())hex.append(String.format("%02x",b&255));return hex.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void writeQuoted(Writer out,String value)throws IOException {
        out.write('"');int start=0;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);String escape;
            switch(c){case '"':escape="\\\"";break;case '\\':escape="\\\\";break;case '\b':escape="\\b";break;case '\f':escape="\\f";break;case '\n':escape="\\n";break;case '\r':escape="\\r";break;case '\t':escape="\\t";break;default:escape=null;}
            if(escape!=null){out.write(value,start,i-start);out.write(escape);start=i+1;}
            else if(c<32){out.write(value,start,i-start);out.write("\\u00");out.write(Character.forDigit(c>>>4,16));out.write(Character.forDigit(c&15,16));start=i+1;}
        }
        out.write(value,start,value.length()-start);out.write('"');
    }
    static String sha(byte[] bytes) {
        try {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    /** Hash a parsed declaration with the same binary64 spelling as the Python producer. */
    static String nodeHash(com.fasterxml.jackson.databind.JsonNode value) {
        try{return com.consense.common.Utf8.sha256(nodeCanonical(value));}
        catch(IOException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    /** Reconstruct a negotiated wire view with Python's canonical scalar spelling and one enclosing output. */
    static String nodeWire(com.fasterxml.jackson.databind.JsonNode value){
        try{LengthWriter length=new LengthWriter();writeNode(length,value);
            StringWriter out=new StringWriter(Math.toIntExact(length.length));writeNode(out,value);return out.toString();}
        catch(IOException|IllegalArgumentException invalid){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    /** Stored normal snapshots expose their two authority headers within the bounded display-read prefix. */
    static String publicationWire(com.fasterxml.jackson.databind.JsonNode value){
        try{LengthWriter length=new LengthWriter();writePublication(length,value);
            StringWriter out=new StringWriter(Math.toIntExact(length.length));writePublication(out,value);return out.toString();}
        catch(IOException|IllegalArgumentException invalid){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    private static void writePublication(Writer out,com.fasterxml.jackson.databind.JsonNode value)throws IOException{
        if(!value.isObject()||!value.has("schema")||!value.has("producerKind"))throw new IOException("Missing publication authority header");
        out.write('{');boolean first=true;
        for(String header:Arrays.asList("schema","producerKind")){
            if(!first)out.write(',');first=false;writeQuoted(out,header);out.write(':');writeNode(out,value.get(header));
        }
        Map<String,com.fasterxml.jackson.databind.JsonNode> fields=new TreeMap<>(CanonicalJson::compareCodepoints);
        value.fields().forEachRemaining(row->{if(!"schema".equals(row.getKey())&&!"producerKind".equals(row.getKey()))fields.put(row.getKey(),row.getValue());});
        for(Map.Entry<String,com.fasterxml.jackson.databind.JsonNode> row:fields.entrySet()){
            out.write(',');writeQuoted(out,row.getKey());out.write(':');writeNode(out,row.getValue());
        }out.write('}');
    }
    private static void writeNode(Writer out,com.fasterxml.jackson.databind.JsonNode value)throws IOException{
        if(value.isObject()){
            Map<String,com.fasterxml.jackson.databind.JsonNode> fields=new TreeMap<>(CanonicalJson::compareCodepoints);value.fields().forEachRemaining(row->fields.put(row.getKey(),row.getValue()));
            out.write('{');boolean first=true;for(Map.Entry<String,com.fasterxml.jackson.databind.JsonNode> row:fields.entrySet()){
                if(!first)out.write(',');first=false;writeQuoted(out,row.getKey());out.write(':');writeNode(out,row.getValue());}out.write('}');return;
        }
        if(value.isArray()){out.write('[');boolean first=true;for(com.fasterxml.jackson.databind.JsonNode item:value){if(!first)out.write(',');first=false;writeNode(out,item);}out.write(']');return;}
        if(value.isFloatingPointNumber()){out.write(pythonFloat(value.asText()));return;}
        if(value.isIntegralNumber()){out.write(value.bigIntegerValue().toString());return;}
        if(value.isTextual()){writeQuoted(out,value.asText());return;}
        if(value.isBoolean()){out.write(value.asBoolean()?"true":"false");return;}
        if(value.isNull()){out.write("null");return;}
        throw new IOException("Unsupported parsed declaration node");
    }
    private static String nodeCanonical(com.fasterxml.jackson.databind.JsonNode value)throws IOException {
        if(value.isObject()){Map<String,String> fields=new HashMap<>();java.util.Iterator<Map.Entry<String,com.fasterxml.jackson.databind.JsonNode>> rows=value.fields();while(rows.hasNext()){Map.Entry<String,com.fasterxml.jackson.databind.JsonNode> row=rows.next();fields.put(row.getKey(),nodeCanonical(row.getValue()));}return rawObject(fields);}
        if(value.isArray()){List<String> values=new ArrayList<>();for(com.fasterxml.jackson.databind.JsonNode item:value)values.add(nodeCanonical(item));return rawArray(values);}
        if(value.isFloatingPointNumber())return pythonFloat(value.asText());
        if(value.isIntegralNumber())return value.bigIntegerValue().toString();
        if(value.isTextual())return quote(value.asText());
        if(value.isBoolean())return value.asBoolean()?"true":"false";
        if(value.isNull())return "null";
        throw new IOException("Unsupported parsed declaration node");
    }
    /** Strict Python-produced wire only. Existing hash/read behavior above remains unchanged. */
    static String strictCanonical(String json) {
        try(JsonParser parser=FACTORY.createParser(json)) {
            parser.nextToken();String value=strictRead(parser);
            if(parser.nextToken()!=null)throw new IOException("Trailing JSON");
            return value;
        }catch(IOException|IllegalArgumentException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static void requireCanonical(String raw) {
        if(!strictCanonical(raw).equals(raw))throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_NONCANONICAL");
    }
    static Map<String,String> rawFields(String json) {
        requireCanonical(json);
        try(JsonParser p=FACTORY.createParser(json)) {
            if(p.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Expected object");
            Map<String,String> out=new TreeMap<>(CanonicalJson::compareCodepoints);
            while(p.nextToken()!=JsonToken.END_OBJECT){String name=p.getCurrentName();p.nextToken();out.put(name,read(p,null));}
            if(p.nextToken()!=null)throw new IOException("Trailing JSON");return out;
        }catch(IOException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static List<String> rawElements(String json) {
        requireCanonical(json);
        try(JsonParser p=FACTORY.createParser(json)) {
            if(p.nextToken()!=JsonToken.START_ARRAY)throw new IOException("Expected array");
            List<String> out=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY)out.add(read(p,null));
            if(p.nextToken()!=null)throw new IOException("Trailing JSON");return out;
        }catch(IOException error){throw new CanonicalEvidenceException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CANONICAL_JSON_INVALID");}
    }
    static String rawObject(Map<String,String> fields) {
        Map<String,String> sorted=new TreeMap<>(CanonicalJson::compareCodepoints);sorted.putAll(fields);
        long length=2L+Math.max(0,sorted.size()-1);for(Map.Entry<String,String> row:sorted.entrySet())length+=quotedLength(row.getKey())+1+String.valueOf(row.getValue()).length();
        StringBuilder out=new StringBuilder(Math.toIntExact(length));out.append('{');boolean first=true;
        for(Map.Entry<String,String> row:sorted.entrySet()){if(!first)out.append(',');first=false;appendQuoted(out,row.getKey());out.append(':').append(row.getValue());}
        return out.append('}').toString();
    }
    static String rawArray(List<String> values){
        long length=2L+Math.max(0,values.size()-1);for(String value:values)length+=String.valueOf(value).length();
        StringBuilder out=new StringBuilder(Math.toIntExact(length));out.append('[');boolean first=true;
        for(String value:values){if(!first)out.append(',');first=false;out.append(value);}return out.append(']').toString();
    }
    static String scalar(String value){return quote(value);}
    private static String strictRead(JsonParser p)throws IOException {
        JsonToken token=p.currentToken();
        if(token==JsonToken.START_OBJECT){Map<String,String> out=new TreeMap<>(CanonicalJson::compareCodepoints);
            while(p.nextToken()!=JsonToken.END_OBJECT){String name=p.getCurrentName();p.nextToken();out.put(name,strictRead(p));}return rawObject(out);}
        if(token==JsonToken.START_ARRAY){List<String> out=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY)out.add(strictRead(p));return rawArray(out);}
        if(token==JsonToken.VALUE_NUMBER_INT){String raw=p.getText();if(!new BigInteger(raw).toString().equals(raw))throw new IOException("Noncanonical integer");return raw;}
        if(token==JsonToken.VALUE_NUMBER_FLOAT){String raw=p.getText();if(!pythonFloat(raw).equals(raw))throw new IOException("Noncanonical float");return raw;}
        if(token==JsonToken.VALUE_STRING)return quote(p.getText());
        if(token==JsonToken.VALUE_TRUE)return "true";if(token==JsonToken.VALUE_FALSE)return "false";if(token==JsonToken.VALUE_NULL)return "null";
        throw new IOException("Invalid JSON token");
    }
    /** Shortest nearest decimal that roundtrips to the binary64 value, with Python repr formatting. */
    private static String pythonFloat(String raw)throws IOException {
        double value=Double.parseDouble(raw);if(!Double.isFinite(value))throw new IOException("Nonfinite float");
        if(value==0.0)return Double.doubleToRawLongBits(value)<0?"-0.0":"0.0";
        BigDecimal exact=new BigDecimal(Math.abs(value)),shortest=null;
        long bits=Double.doubleToRawLongBits(Math.abs(value));
        int decimalExponent=exact.precision()-exact.scale()-1;
        for(int digits=1;digits<=17;digits++){
            BigDecimal nearest=exact.round(new MathContext(digits,RoundingMode.HALF_EVEN));
            BigDecimal step=BigDecimal.ONE.scaleByPowerOfTen(decimalExponent-digits+1),best=null,bestDistance=null;
            // Binary64 intervals are asymmetric at powers of two. The nearest
            // decimal may miss that interval while an adjacent decimal fits.
            for(int neighbor=-1;neighbor<=1;neighbor++){
                BigDecimal candidate=nearest.add(step.multiply(BigDecimal.valueOf(neighbor))).stripTrailingZeros();
                if(candidate.signum()<=0||candidate.precision()>digits
                        ||Double.doubleToRawLongBits(Double.parseDouble(candidate.toString()))!=bits)continue;
                BigDecimal distance=exact.subtract(candidate).abs();
                int comparison=bestDistance==null?-1:distance.compareTo(bestDistance);
                if(comparison<0||(comparison==0&&!candidate.unscaledValue().testBit(0))){best=candidate;bestDistance=distance;}
            }
            if(best!=null){shortest=best;break;}
        }
        if(shortest==null)throw new IOException("Float roundtrip unavailable");
        String digits=shortest.unscaledValue().abs().toString();int exponent=digits.length()-shortest.scale()-1;
        String sign=value<0?"-":"";
        if(exponent< -4||exponent>=16){String mantissa=digits.substring(0,1)+(digits.length()>1?"."+digits.substring(1):"");
            return sign+mantissa+"e"+(exponent<0?"-":"+")+String.format(java.util.Locale.ROOT,"%02d",Math.abs(exponent));}
        String plain=shortest.toPlainString();return sign+plain+(plain.contains(".")?"":".0");
    }
    private static String read(JsonParser p,String omit) throws IOException {
        JsonToken token=p.currentToken();
        if(token==JsonToken.START_OBJECT){
            Map<String,String> fields=new TreeMap<>(CanonicalJson::compareCodepoints);
            while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.getCurrentName();p.nextToken();String value=read(p,null);if(!key.equals(omit))fields.put(key,value);}
            return rawObject(fields);
        }
        if(token==JsonToken.START_ARRAY){List<String> values=new ArrayList<>();while(p.nextToken()!=JsonToken.END_ARRAY)values.add(read(p,null));return rawArray(values);}
        if(token==JsonToken.VALUE_STRING)return quote(p.getText());
        if(token==JsonToken.VALUE_NUMBER_INT||token==JsonToken.VALUE_NUMBER_FLOAT)return p.getText();
        if(token==JsonToken.VALUE_TRUE)return "true";if(token==JsonToken.VALUE_FALSE)return "false";if(token==JsonToken.VALUE_NULL)return "null";
        throw new IOException("Invalid JSON token");
    }
    private static int compareCodepoints(String a,String b){int x=0,y=0;while(x<a.length()&&y<b.length()){int ca=a.codePointAt(x),cb=b.codePointAt(y);if(ca!=cb)return Integer.compare(ca,cb);x+=Character.charCount(ca);y+=Character.charCount(cb);}return Integer.compare(a.length()-x,b.length()-y);}
    private static long quotedLength(String value){long length=2;for(int i=0;i<value.length();i++){char c=value.charAt(i);length+=c=='"'||c=='\\'||c=='\b'||c=='\f'||c=='\n'||c=='\r'||c=='\t'?2:c<32?6:1;}return length;}
    private static String quote(String value){StringBuilder out=new StringBuilder(Math.toIntExact(quotedLength(value)));appendQuoted(out,value);return out.toString();}
    private static void appendQuoted(StringBuilder b,String value){b.append('"');for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}b.append('"');}
}
