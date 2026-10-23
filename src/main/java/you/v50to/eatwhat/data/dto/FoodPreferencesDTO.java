package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Complete replacement; send two empty arrays to clear explicit tastes. */
public record FoodPreferencesDTO(
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 32) String> likedTags,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 32) String> dislikedTags) { }
