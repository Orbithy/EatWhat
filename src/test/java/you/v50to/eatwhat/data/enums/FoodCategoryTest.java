package you.v50to.eatwhat.data.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FoodCategoryTest {

    @Test
    void shouldAcceptLowercaseValue() {
        assertEquals(FoodCategory.GRILL, FoodCategory.fromValue("grill"));
    }

    @Test
    void shouldAcceptUppercaseValue() {
        assertEquals(FoodCategory.GRILL, FoodCategory.fromValue("GRILL"));
    }

    @Test
    void shouldAcceptMixedCaseValueWithWhitespace() {
        assertEquals(FoodCategory.HOT_POT, FoodCategory.fromValue("  HoT_PoT  "));
    }

    @Test
    void shouldRejectUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> FoodCategory.fromValue("unknown"));
    }
}
