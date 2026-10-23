package you.v50to.eatwhat.data.dto;

import lombok.Data;

/** Explicit account choices only; no inferred features or behavior statistics. */
@Data
public class FoodPreferencesRow {
    private String[] likedTags;
    private String[] dislikedTags;
    private Long updatedAt;
}
