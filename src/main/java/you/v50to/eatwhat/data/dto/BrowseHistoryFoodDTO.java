package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class BrowseHistoryFoodDTO {
    private Long id;
    private String name;
    private List<String> pictureUrl;
    private Integer likesCount;
}
