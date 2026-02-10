package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.dto.PresignUploadReqDTO;
import you.v50to.eatwhat.data.dto.PresignUploadRespDTO;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

@SaCheckLogin
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private ObjectStorageService objectStorageService;

    @PostMapping("/presign")
    public Result<PresignUploadRespDTO> presign(@Valid @RequestBody PresignUploadReqDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        ObjectStorageService.PresignedUpload presigned = objectStorageService.presignPut(
                userId,
                dto.getBiz(),
                dto.getFileName(),
                dto.getContentType(),
                dto.getFileSize()
        );
        PresignUploadRespDTO resp = new PresignUploadRespDTO(presigned.putUrl(), presigned.key(), presigned.expireAt());
        return Result.ok(resp);
    }
}
