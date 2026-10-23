package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.data.dto.NotificationDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Notification;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.NotificationMapper;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import you.v50to.eatwhat.sync.notification.NotificationReadCommands;
import you.v50to.eatwhat.service.storage.ObjectStorageService;

import java.util.List;

@Service
public class NotificationService {

    @Resource
    private NotificationMapper notificationMapper;
    @Resource
    private ObjectStorageService objectStorageService;
    private NotificationReadCommands notificationReadCommands;
    private ManagedDomainWriteGuard managedDomainWriteGuard;

    @Autowired(required = false)
    public void setNotificationReadCommands(NotificationReadCommands notificationReadCommands) {
        this.notificationReadCommands = notificationReadCommands;
    }

    @Autowired(required = false)
    public void setManagedDomainWriteGuard(ManagedDomainWriteGuard managedDomainWriteGuard) {
        this.managedDomainWriteGuard = managedDomainWriteGuard;
    }

    public Result<PageResult<NotificationDTO>> listMyNotifications(Integer page, Integer pageSize) {
        page = validPage(page);
        pageSize = validPageSize(pageSize);

        Long accountId = StpUtil.getLoginIdAsLong();
        int offset = (page - 1) * pageSize;

        List<NotificationDTO> items = notificationMapper.selectByAccountId(accountId, offset, pageSize);
        signActorAvatar(items);
        Long totalItems = notificationMapper.countByAccountId(accountId);

        return Result.ok(PageResult.of(items, page.longValue(), pageSize.longValue(), totalItems));
    }

    public Result<Long> countUnread() {
        Long accountId = StpUtil.getLoginIdAsLong();
        Long unread = notificationMapper.countUnreadByAccountId(accountId);
        return Result.ok(unread == null ? 0L : unread);
    }

    @Transactional
    public Result<Void> markOneAsRead(Long id) {
        Long accountId = StpUtil.getLoginIdAsLong();
        if (notificationReadCommands == null) {
            if (managedDomainWriteGuard != null && managedDomainWriteGuard.notificationManaged(accountId)) {
                if (id != null && notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getAccountId, accountId).eq(Notification::getId, id)
                        .isNull(Notification::getReadAt)) > 0) {
                    return Result.fail(BizCode.DATA_SYNC_PENDING);
                }
            } else {
                notificationMapper.markOneAsRead(accountId, id);
            }
        } else notificationReadCommands.markOne(accountId, id);
        return Result.ok();
    }

    @Transactional
    public Result<Void> markAllAsRead() {
        Long accountId = StpUtil.getLoginIdAsLong();
        if (notificationReadCommands == null) {
            if (managedDomainWriteGuard != null && managedDomainWriteGuard.notificationManaged(accountId)) {
                if (notificationMapper.countUnreadByAccountId(accountId) > 0) {
                    return Result.fail(BizCode.DATA_SYNC_PENDING);
                }
            } else {
                notificationMapper.markAllAsRead(accountId);
            }
        } else notificationReadCommands.markAll(accountId);
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
