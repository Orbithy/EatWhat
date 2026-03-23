package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName("food_favorites")
public class FoodFavorite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long foodId;
    private OffsetDateTime createdAt;
}
