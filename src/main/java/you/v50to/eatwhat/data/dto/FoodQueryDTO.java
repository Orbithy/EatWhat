package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import you.v50to.eatwhat.data.enums.FoodCategory;
import java.math.BigDecimal;
import java.util.List;

/** All supplied filters are combined with AND; tag lists require every distinct tag. */
@Data
public class FoodQueryDTO {
    public enum Sort { NEWEST, PRICE_ASC, PRICE_DESC, POPULAR, FAVORITED_AT }
    @Min(1) private Long restaurantId;
    @Min(1) private Long hubId;
    @Size(max = 64) private String keyword;
    private FoodCategory category;
    @DecimalMin("0") @Digits(integer = 6, fraction = 2) private BigDecimal minPrice;
    @DecimalMin("0") @Digits(integer = 6, fraction = 2) private BigDecimal maxPrice;
    private Sort sort;
    @Min(1) private Integer page = 1;
    @Min(1) @Max(100) private Integer pageSize = 20;
    @Size(max = 20) private List<@NotNull @Min(1) Long> systemTagIds;
    @Size(max = 20) private List<@NotNull @Min(1) Long> myCustomTagIds;
    @Size(max = 20) private List<@NotBlank @Size(max = 64) String> myCustomTagNames;
}
