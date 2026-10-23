package you.v50to.eatwhat.account;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Versioned public notice only; no account data or derived recommendation profile. */
public final class BrowseConsentPolicy {
    public static final String VERSION = "cross-border-browse-v2";
    public static final String DOCUMENT = "/policies/cross-border-browse-v2.html";
    private static final Map<String, Object> NOTICE = Map.ofEntries(
            Map.entry("version", VERSION),
            Map.entry("title", "EatWhat 跨站数据同步授权说明（含浏览历史）"),
            Map.entry("effectiveDate", "2026-10-20"),
            Map.entry("documentPath", DOCUMENT),
            Map.entry("documentSha256", documentHash()),
            Map.entry("recipient", "EatWhat 校内站与新加坡站；向新加坡站同步属于跨境传输"),
            Map.entry("purpose", "跨站使用账号和业务功能，并依据获准的点赞、收藏、口味标签及浏览行为进行后端个性化推荐"),
            Map.entry("browsingFields", List.of("用户标识", "菜品或餐厅的类型及标识", "原始浏览时间", "同步去重标识与授权版本")),
            Map.entry("recommendationWindow", "推荐使用近30天的浏览信号；浏览历史本身当前不按30天自动删除"),
            Map.entry("profileVisibility", "自动推断的画像仅供后端推荐使用，不向用户展示；手动口味设置仍按站点保存"),
            Map.entry("choice", "校内可不同意并继续本地使用；新加坡普通账号功能需要有效跨站授权。旧版本授权不会自动升级"),
            Map.entry("withdrawal", "可通过现有跨站授权撤回入口撤回；停止后续同步、限制新加坡账号功能，并清理境外业务投影、浏览历史及其去重映射。校内本地历史保留"),
            Map.entry("retention", "同步防重放账本、授权墓碑、审计记录和既有备份按现有受限保留机制处理；撤回不表示所有日志和备份立即物理抹除"),
            Map.entry("excluded", List.of("会话令牌", "验证码", "明文密码", "浏览页面完整URL", "详情请求正文", "独立导出的自动画像")));

    private BrowseConsentPolicy() { }
    public static Map<String, Object> notice() { return NOTICE; }

    private static String documentHash() {
        try (var stream = BrowseConsentPolicy.class.getResourceAsStream("/static" + DOCUMENT)) {
            if (stream == null) throw new IllegalStateException("CONSENT_NOTICE_DOCUMENT_REQUIRED");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (Exception failure) { throw new IllegalStateException("CONSENT_NOTICE_DOCUMENT_UNAVAILABLE"); }
    }
}
