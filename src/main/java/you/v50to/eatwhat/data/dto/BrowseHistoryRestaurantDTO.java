package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class BrowseHistoryRestaurantDTO {
    private Long id;
    private String name;
    private List<String> pictureUrl;
}
