package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PresignUploadReqDTO {
    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名长度不能超过255")
    private String fileName;

    @NotBlank(message = "文件类型不能为空")
    @Size(max = 128, message = "文件类型长度不能超过128")
    private String contentType;

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于0")
    private Long fileSize;

    @Pattern(regexp = "^(activity|avatar)$", message = "biz仅支持activity或avatar")
    private String biz = "activity";
}
