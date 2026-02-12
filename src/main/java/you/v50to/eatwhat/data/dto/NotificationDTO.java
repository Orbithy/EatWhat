package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class NotificationDTO {
    private Long id;
    private Long actorId;
    private String actorName;
    private String actorAvatar;
    private String type;
    private String targetType;
    private Long targetId;
    private String data;
    private Boolean read;
    private Long createdAt;
}
