package you.v50to.eatwhat.data.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadBizTest {

    @Test
    void shouldAcceptLowercaseValue() {
        assertEquals(UploadBiz.FOODS, UploadBiz.fromValue("foods"));
    }

    @Test
    void shouldAcceptUppercaseValue() {
        assertEquals(UploadBiz.FOODS, UploadBiz.fromValue("FOODS"));
    }

    @Test
    void shouldAcceptMixedCaseValueWithWhitespace() {
        assertEquals(UploadBiz.FOODS, UploadBiz.fromValue("  FoOdS  "));
    }

    @Test
    void shouldRejectUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> UploadBiz.fromValue("unknown"));
    }
}
