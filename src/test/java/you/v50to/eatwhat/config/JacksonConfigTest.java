package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.utils.GeoUtil;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonConfigTest {

    @Test
    void shouldSerializeJtsPointWithoutTouchingBoundary() throws Exception {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setLocation(GeoUtil.createPoint(116.397, 39.908));

        String json = new JacksonConfig().objectMapper().writeValueAsString(restaurant);

        assertTrue(json.contains("\"location\":{\"type\":\"Point\",\"coordinates\":[116.397,39.908],\"srid\":4326}"));
    }
}
