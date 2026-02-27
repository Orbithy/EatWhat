package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.data.dto.NotificationDTO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.NotificationMapper;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.util.List;

@Service
public class NotificationService {

    @Resource
    private NotificationMapper notificationMapper;
    @Resource
    private ObjectStorageService objectStorageService;

    public Result<PageResult<NotificationDTO>> listMyNotifications(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        Long accountId = StpUtil.getLoginIdAsLong();
        int offset = (page - 1) * pageSize;

        List<NotificationDTO> items = notificationMapper.selectByAccountId(accountId, offset, pageSize);
        signActorAvatar(items);
        Long totalItems = notificationMapper.countByAccountId(accountId);

        return Result.ok(PageResult.of(items, page, pageSize, totalItems));
    }

    public Result<Long> countUnread() {
        Long accountId = StpUtil.getLoginIdAsLong();
        Long unread = notificationMapper.countUnreadByAccountId(accountId);
        return Result.ok(unread == null ? 0L : unread);
    }

    @Transactional
    public Result<Void> markOneAsRead(Long id) {
        Long accountId = StpUtil.getLoginIdAsLong();
        notificationMapper.markOneAsRead(accountId, id);
        return Result.ok();
    }

    @Transactional
    public Result<Void> markAllAsRead() {
        Long accountId = StpUtil.getLoginIdAsLong();
        notificationMapper.markAllAsRead(accountId);
        return Result.ok();
    }

    public void notifyLike(Long actorId, Long receiverId, String targetType, Long targetId) {
        if (actorId == null || receiverId == null || actorId.equals(receiverId)) {
            return;
        }
        notificationMapper.insertNotification(
                receiverId,
                actorId,
                "like",
                targetType,
                targetId,
                "{\"targetType\":\"" + targetType + "\",\"targetId\":" + targetId + "}");
    }

    public void notifyFollow(Long actorId, Long receiverId) {
        if (actorId == null || receiverId == null || actorId.equals(receiverId)) {
            return;
        }
        notificationMapper.insertNotification(
                receiverId,
                actorId,
                "follow",
                "user",
                actorId,
                "{}");
    }

    private void signActorAvatar(List<NotificationDTO> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (NotificationDTO item : items) {
            item.setActorAvatar(objectStorageService.signGetUrl(item.getActorAvatar()));
        }
    }

    private Integer validPage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    private Integer validPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }
}
