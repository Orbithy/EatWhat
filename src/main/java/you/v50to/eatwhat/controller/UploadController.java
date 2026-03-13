package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.data.dto.PresignBatchUploadReqDTO;
import you.v50to.eatwhat.data.dto.PresignBatchUploadRespDTO;
import you.v50to.eatwhat.data.dto.PresignUploadReqDTO;
import you.v50to.eatwhat.data.dto.PresignUploadRespDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.util.List;

/**
 * 上传预签名接口。
 * <ul>
 * <li>biz=avatar — 仅需登录（未验证用户也可更换头像）</li>
 * <li>biz=activity — 需要完成 SSO / 邮箱验证（拥有 verified 角色）</li>
 * </ul>
 */

@SaCheckLogin
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private ObjectStorageService objectStorageService;

    @PostMapping("/presign")
    public Result<PresignUploadRespDTO> presign(@Valid @RequestBody PresignUploadReqDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        if ("activity".equals(dto.getBiz()) && !StpUtil.hasRole("verified")) {
            throw new BizException(BizCode.STATE_NOT_ALLOWED, "上传活动照片需要完成身份认证");
        }
        ObjectStorageService.PresignedUpload presigned = objectStorageService.presignPut(
                userId,
                dto.getBiz(),
                dto.getFileName(),
                dto.getContentType(),
                dto.getFileSize());
        PresignUploadRespDTO resp = new PresignUploadRespDTO(presigned.putUrl(), presigned.key(), presigned.expireAt());
        return Result.ok(resp);
    }

    @PostMapping("/presign/batch")
    public Result<PresignBatchUploadRespDTO> presignBatch(@Valid @RequestBody PresignBatchUploadReqDTO dto) {
        if (CollectionUtils.isEmpty(dto.getFiles())) {
            throw new BizException(BizCode.PARAM_INVALID, "文件列表不能为空");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        if ("activity".equals(dto.getBiz()) && !StpUtil.hasRole("verified")) {
            throw new BizException(BizCode.STATE_NOT_ALLOWED, "上传活动照片需要完成身份认证");
        }
        List<PresignUploadRespDTO> files = dto.getFiles().stream()
                .map(file -> {
                    ObjectStorageService.PresignedUpload presigned = objectStorageService.presignPut(
                            userId,
                            dto.getBiz(),
                            file.getFileName(),
                            file.getContentType(),
                            file.getFileSize());
                    return new PresignUploadRespDTO(presigned.putUrl(), presigned.key(), presigned.expireAt());
                })
                .toList();
        return Result.ok(new PresignBatchUploadRespDTO(files));
    }
}
