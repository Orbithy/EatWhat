package you.v50to.eatwhat.account;

import java.security.MessageDigest;
import java.util.*;

/** Versioned scope expansion; configuration never upgrades a user's existing authorization. */
public final class PreferenceConsentPolicy {
    public static final String VERSION="cross-border-preferences-v3";
    public static final String DOCUMENT="/policies/cross-border-preferences-v3.html";
    private static final Map<String,Object> NOTICE=build();
    private PreferenceConsentPolicy() { }
    public static Map<String,Object> notice(){return NOTICE;}
    private static Map<String,Object> build(){
        var notice=new LinkedHashMap<String,Object>(BrowseConsentPolicy.notice());
        notice.put("version",VERSION);notice.put("title","EatWhat 跨站数据同步授权说明（含浏览与手动口味）");
        notice.put("documentPath",DOCUMENT);
        try(var stream=PreferenceConsentPolicy.class.getResourceAsStream("/static"+DOCUMENT)){
            if(stream==null)throw new IllegalStateException();
            notice.put("documentSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
        }catch(Exception unavailable){throw new IllegalStateException("CONSENT_NOTICE_DOCUMENT_UNAVAILABLE");}
        notice.put("manualPreferenceFields",List.of("用户标识","手动喜欢的口味","手动不喜欢的口味","原始设置时间","同步版本和授权版本"));
        notice.put("profileVisibility","自动画像仅供后端推荐使用，不向用户展示；手动口味设置及清空操作在获准站点间同步");
        notice.put("preferenceConflicts","保存替换整份手动设置；并发按逻辑版本、来源站和事件标识选定同一完整版本。收到对端更新后再次保存可覆盖；不以两站时钟决定输赢");
        notice.put("withdrawal","可通过现有授权撤回入口撤回；停止后续同步、限制新加坡账号功能，并清理境外业务投影、浏览及手动口味。校内本地数据保留");
        return Map.copyOf(notice);
    }
}
