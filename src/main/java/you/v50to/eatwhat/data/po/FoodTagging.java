package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName("food_taggings")
public class FoodTagging {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long foodId;

    private Long tagId;

    private Long accountId;

    private OffsetDateTime createdAt;
}
