package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ActivityDinnerDTO {
    private Long id;
    private Long accountId;
    private List<String> pictureUrl;
    private String description;
    private Integer likesCount;
    private Long createdAt;
    private Long updatedAt;
}
