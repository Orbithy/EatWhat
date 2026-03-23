package you.v50to.eatwhat.data.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@TableName("food_tags")
public class FoodTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String tagType;

    private Long ownerId;

    private String normalizedName;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
