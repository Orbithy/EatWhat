package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AddSystemFoodTagsDTO {

    @NotEmpty(message = "系统标签不能为空")
    @Size(max = 20, message = "一次最多添加20个系统标签")
    private List<@Min(value = 1, message = "标签ID必须大于0") Long> tagIds;
}
