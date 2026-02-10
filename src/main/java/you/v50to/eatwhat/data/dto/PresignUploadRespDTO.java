package you.v50to.eatwhat.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PresignUploadRespDTO {
    private String putUrl;
    private String key;
    private Long expireAt;
}
