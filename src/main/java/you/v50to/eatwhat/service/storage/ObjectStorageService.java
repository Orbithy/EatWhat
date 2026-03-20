package you.v50to.eatwhat.service.storage;

import you.v50to.eatwhat.data.enums.UploadBiz;

import java.util.List;

public interface ObjectStorageService {
    String signGetUrl(String key);

    List<String> signGetUrls(List<String> keys);

    PresignedUpload presignPut(Long userId, UploadBiz biz, String fileName, String contentType, Long fileSize);

    record PresignedUpload(String putUrl, String key, Long expireAt) {
    }
}
