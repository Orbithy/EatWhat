package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapNativeJsonTest {
    @Test void hashesMatchPythonForUnicodeKeyOrderingEscapesAndLargeIntegers()throws Exception{
        var value=new LinkedHashMap<String,Object>();
        // Supplementary characters sort after BMP characters in Python, unlike
        // Java's natural UTF-16 String order. DEL must also be ASCII-escaped.
        value.put("\ud800\udc00","繁體😀\u007f/\"\\\b\f\n\r\t");value.put("\ue000",List.of(1,-2L,true,false));
        value.put("empty",Map.of());value.put("null",null);value.put("big",new java.math.BigInteger("18446744073709551616"));
        byte[] raw=JsonMapper.builder().build().writeValueAsBytes(value);
        String python="import sys,json;v=json.load(sys.stdin);sys.stdout.write(json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True))";
        String expected=new String(ControlSsh.process(List.of("/usr/bin/python3","-I","-c",python),Map.of(),out->out.write(raw),10,4096,true),StandardCharsets.US_ASCII);
        assertEquals(expected,ControlBootstrapNativeJson.encode(value));
    }
    @Test void unsupportedTypesCannotSilentlyChangeANativeDigest(){
        for(Object value:List.of(0.1,Double.NaN,Set.of("not-an-array"),Map.of(1,"not-a-string-key")))
            assertEquals("BOOTSTRAP_NATIVE_INVALID_INPUT",assertThrows(ControlFailure.class,()->ControlBootstrapNativeJson.encode(value)).code);
    }
}
