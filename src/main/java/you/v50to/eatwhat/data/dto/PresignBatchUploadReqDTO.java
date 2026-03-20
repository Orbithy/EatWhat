package you.v50to.eatwhat.data.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import you.v50to.eatwhat.data.enums.UploadBiz;

import java.util.List;

@Data
public class PresignBatchUploadReqDTO {
    @NotNull(message = "biz不能为空")
    private UploadBiz biz = UploadBiz.ACTIVITY;

    @NotNull(message = "文件列表不能为空")
    @Size(min = 1, max = 9, message = "图片数量需在1-9张之间")
    private List<@Valid PresignBatchUploadFileDTO> files;
}
