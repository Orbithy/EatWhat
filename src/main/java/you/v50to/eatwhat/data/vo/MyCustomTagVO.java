package you.v50to.eatwhat.data.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyCustomTagVO {
    private Long id;
    private String name;
    private Long usageCount;
}

