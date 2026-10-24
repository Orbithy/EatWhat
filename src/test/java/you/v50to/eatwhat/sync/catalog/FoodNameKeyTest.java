package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FoodNameKeyTest {
    @Test void whitespaceDoesNotChangeNaturalIdentityButRestaurantAndSpellingDo() {
        String parent=UUID.randomUUID().toString();
        var original=CatalogPayload.naturalKey(CatalogKind.FOOD,Map.of("name","鲜虾云吞"),null,parent);
        for(String name:java.util.List.of(" 鲜虾 云吞 ","鲜虾　云吞","鲜虾\t云\n吞")) {
            assertEquals(original,CatalogPayload.naturalKey(CatalogKind.FOOD,Map.of("name",name),null,parent));
        }
        assertNotEquals(original,CatalogPayload.naturalKey(CatalogKind.FOOD,Map.of("name","鲜虾云吞"),null,UUID.randomUUID().toString()));
        assertNotEquals(original,CatalogPayload.naturalKey(CatalogKind.FOOD,Map.of("name","鲜肉云吞"),null,parent));
    }
}
