package you.v50to.eatwhat.service.activity;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.data.po.Activity;
import you.v50to.eatwhat.data.vo.ActivityVO;

import java.util.List;
import java.util.Map;

/** 活动定义的状态和视图转换，供管理接口和公开接口共用。 */
public final class ActivityDefinitions {
    public static final String DRAFT = "DRAFT";
    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";
    private static final TypeReference<List<Map<String, Object>>> SCHEMA = new TypeReference<>() { };

    private ActivityDefinitions() { }

    /** 已上线且在发布时间窗内（边界含开始、不含结束）。 */
    public static boolean postingOpen(Activity activity, long nowMillis) {
        return ONLINE.equals(activity.getStatus())
                && (activity.getStartsAt() == null || nowMillis >= activity.getStartsAt())
                && (activity.getEndsAt() == null || nowMillis < activity.getEndsAt());
    }

    /** 公开接口只展示已上线和已下线的活动；草稿只有管理员能看到。 */
    public static boolean publiclyReadable(Activity activity) {
        return ONLINE.equals(activity.getStatus()) || OFFLINE.equals(activity.getStatus());
    }

    public static List<Map<String, Object>> schema(Activity activity, ObjectMapper objectMapper) {
        String raw = activity.getFieldSchema();
        if (raw == null || raw.isBlank()) return List.of();
        return objectMapper.readValue(raw, SCHEMA);
    }

    public static ActivityVO toVO(Activity activity, ObjectMapper objectMapper, long nowMillis) {
        ActivityVO vo = new ActivityVO();
        vo.setId(activity.getId());
        vo.setCode(activity.getCode());
        vo.setName(activity.getName());
        vo.setDescription(activity.getDescription());
        vo.setCoverUrl(activity.getCoverUrl());
        vo.setStatus(activity.getStatus());
        vo.setStartsAt(activity.getStartsAt());
        vo.setEndsAt(activity.getEndsAt());
        vo.setPostingOpen(postingOpen(activity, nowMillis));
        vo.setSort(activity.getSort());
        vo.setRequireLocation(activity.getRequireLocation());
        vo.setMaxPictures(activity.getMaxPictures());
        vo.setFieldSchema(schema(activity, objectMapper));
        vo.setCreatedAt(activity.getCreatedAt());
        vo.setUpdatedAt(activity.getUpdatedAt());
        return vo;
    }
}
