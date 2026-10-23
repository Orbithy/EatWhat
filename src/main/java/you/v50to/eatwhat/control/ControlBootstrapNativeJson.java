package you.v50to.eatwhat.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** The native protocols hash Python json.dumps(sort_keys=True,
 * separators=(',', ':'), ensure_ascii=True). Headers contain no floating point
 * numbers. Jackson's default Unicode escaping and key order differ. */
final class ControlBootstrapNativeJson {
    private ControlBootstrapNativeJson() {}
    static String encode(Object value) {var out=new StringBuilder();append(out,value);return out.toString();}
    static String digest(Object value) {return sha(encode(value));}
    static String sha(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_NATIVE_INPUT_UNAVAILABLE");}
    }
    private static void append(StringBuilder out,Object value) {
        if(value==null){out.append("null");return;}
        if(value instanceof String text){string(out,text);return;}
        if(value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger){out.append(value);return;}
        if(value instanceof Map<?,?> map){
            var keys=new ArrayList<String>();for(Object key:map.keySet()){
                if(!(key instanceof String text))throw invalid();keys.add(text);
            }
            keys.sort(ControlBootstrapNativeJson::compareCodePoints);out.append('{');boolean comma=false;
            for(String key:keys){if(comma)out.append(',');comma=true;string(out,key);out.append(':');append(out,map.get(key));}
            out.append('}');return;
        }
        if(value instanceof List<?> list){out.append('[');boolean comma=false;for(Object item:list){if(comma)out.append(',');comma=true;append(out,item);}out.append(']');return;}
        throw invalid();
    }
    private static int compareCodePoints(String left,String right){
        int a=0,b=0;while(a<left.length()&&b<right.length()){
            int x=left.codePointAt(a),y=right.codePointAt(b);if(x!=y)return Integer.compare(x,y);
            a+=Character.charCount(x);b+=Character.charCount(y);
        }return Integer.compare(left.length()-a,right.length()-b);
    }
    private static void string(StringBuilder out,String value){
        out.append('"');for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);switch(ch){
                case '"' -> out.append("\\\"");case '\\' -> out.append("\\\\");case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");case '\n' -> out.append("\\n");case '\r' -> out.append("\\r");case '\t' -> out.append("\\t");
                default -> {if(ch<32||ch>=127){out.append("\\u");for(int shift=12;shift>=0;shift-=4)out.append("0123456789abcdef".charAt((ch>>shift)&15));}else out.append(ch);}
            }
        }out.append('"');
    }
    private static ControlFailure invalid(){return new ControlFailure(409,"BOOTSTRAP_NATIVE_INVALID_INPUT");}
}
