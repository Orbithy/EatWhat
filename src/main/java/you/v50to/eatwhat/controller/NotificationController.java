package you.v50to.eatwhat.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import you.v50to.eatwhat.data.dto.NotificationDTO;
import you.v50to.eatwhat.data.vo.PageResult;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.service.NotificationService;

@SaCheckLogin
@RestController
@RequestMapping("/notification")
public class NotificationController {

    @Resource
    private NotificationService notificationService;

    @GetMapping("/list")
    public Result<PageResult<NotificationDTO>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return notificationService.listMyNotifications(page, pageSize);
    }

    @GetMapping("/unread/count")
    public Result<Long> unreadCount() {
        return notificationService.countUnread();
    }

    @PostMapping("/read/{id}")
    public Result<Void> readOne(@PathVariable Long id) {
        return notificationService.markOneAsRead(id);
    }

    @PostMapping("/read/all")
    public Result<Void> readAll() {
        return notificationService.markAllAsRead();
    }
}
