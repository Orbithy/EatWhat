package you.v50to.eatwhat.data.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 其他用户信息
 */
@Data
@NoArgsConstructor
public class OtherUserInfoDTO {
    private String userName;
    private String avatar;
    private Integer earnedLikes;
    private Integer fansCount;
    private Integer followingsCount;
    /**
     * 粉丝列表是否可见
     */
    private Boolean fansVisible;
    /**
     * 关注列表是否可见
     */
    private Boolean followingsVisible;
    /**
     * 是否已关注当前登录用户
     */
    private Boolean isFollowingMe;
    /**
     * 是否已关注对方
     */
    private Boolean isFollowingHim;
    /**
     * 上传店铺数量
     */
    private Long uploadedRestaurantsCount;
    /**
     * 上传菜品数量
     */
    private Long uploadedFoodsCount;
}
