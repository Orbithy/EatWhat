package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ClearBrowseHistoryDTO {
    /** Omit to clear both tabs. */
    @Pattern(regexp = "food|restaurant")
    private String targetType;
}
