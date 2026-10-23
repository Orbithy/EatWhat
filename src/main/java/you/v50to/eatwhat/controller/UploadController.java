package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.dto.PresignBatchUploadReqDTO;
import you.v50to.eatwhat.data.dto.PresignBatchUploadRespDTO;
import you.v50to.eatwhat.data.dto.PresignUploadReqDTO;
import you.v50to.eatwhat.data.dto.PresignUploadRespDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.util.List;

/**
 * 上传预签名接口。
 * <ul>
 * <li>biz=avatar — 仅需登录（未验证用户也可更换头像）</li>
 * <li>biz=activity / foods / restaurants — 需要完成 SSO / 邮箱验证（拥有 verified 角色）</li>
 * </ul>
 */
@SaCheckLogin
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private ObjectStorageService objectStorageService;

    @Resource
    private FileLifecycleService fileLifecycleService;

    @Resource
    private FileLifecycleProperties fileLifecycleProperties = new FileLifecycleProperties();

    @PostMapping("/presign")
    public Result<PresignUploadRespDTO> presign(@Valid @RequestBody PresignUploadReqDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        checkBizPermission(dto.getBiz());
        ObjectStorageService.PresignedUpload presigned = presign(userId, dto.getBiz(), dto.getFileName(), dto.getContentType(), dto.getFileSize());
        PresignUploadRespDTO resp = new PresignUploadRespDTO(presigned.putUrl(), presigned.key(), presigned.expireAt());
        return Result.ok(resp);
    }

    @PostMapping("/presign/batch")
    public Result<PresignBatchUploadRespDTO> presignBatch(@Valid @RequestBody PresignBatchUploadReqDTO dto) {
        if (CollectionUtils.isEmpty(dto.getFiles())) {
            throw new BizException(BizCode.PARAM_INVALID, "文件列表不能为空");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        checkBizPermission(dto.getBiz());
        List<PresignUploadRespDTO> files = dto.getFiles().stream()
                .map(file -> {
                    ObjectStorageService.PresignedUpload presigned = presign(userId, dto.getBiz(), file.getFileName(), file.getContentType(), file.getFileSize());
                    return new PresignUploadRespDTO(presigned.putUrl(), presigned.key(), presigned.expireAt());
                })
                .toList();
        return Result.ok(new PresignBatchUploadRespDTO(files));
    }

    @PostMapping("/complete")
    public Result<FileLifecycleService.CompletedFile> complete(@Valid @RequestBody CompleteUploadRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        checkBizPermission(you.v50to.eatwhat.data.enums.UploadBiz.fromValue(
                fileLifecycleService.getBiz(request.key())));
        FileLifecycleService.CompletedFile completed = fileLifecycleService.complete(userId, request.key());
        return Result.ok(completed);
    }

    private ObjectStorageService.PresignedUpload presign(Long userId, UploadBiz biz, String fileName, String type, Long size) {
        return fileLifecycleProperties.isLifecycleEnabled()
                ? fileLifecycleService.presign(userId, biz, fileName, type, size)
                : objectStorageService.presignPut(userId, biz, fileName, type, size);
    }

    public record CompleteUploadRequest(@NotBlank String key) { }

    private void checkBizPermission(UploadBiz biz) {
        if (biz != null && biz.isRequireVerified()) {
            StpUtil.checkRole("verified");
        }
    }
}
