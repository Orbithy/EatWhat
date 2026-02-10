package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateActivityDinnerDTO {

    @Size(max = 2000, message = "年夜饭介绍长度不能超过2000")
    private String description;

    @NotNull(message = "年夜饭图片不能为空")
    @Size(min = 1, max = 9, message = "年夜饭图片数量需在1-9张之间")
    private List<@NotBlank(message = "图片链接不能为空") String> pictureUrl;
}
