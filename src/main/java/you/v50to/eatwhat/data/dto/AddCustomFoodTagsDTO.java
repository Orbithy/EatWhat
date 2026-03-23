package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AddCustomFoodTagsDTO {

    @NotEmpty(message = "自定义标签不能为空")
    @Size(max = 20, message = "一次最多添加20个自定义标签")
    private List<@NotBlank(message = "标签名称不能为空") @Size(max = 64, message = "标签名称长度不能超过64") String> names;
}
