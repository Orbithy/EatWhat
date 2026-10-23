package you.v50to.eatwhat.account;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class BrowseConsentPolicyTest {
    @Test void publicNoticeIsVersionedAndBoundToThePackagedDocument() throws Exception {
        var notice = BrowseConsentPolicy.notice();
        assertEquals("cross-border-browse-v2", notice.get("version"));
        try (var input = BrowseConsentPolicy.class.getResourceAsStream("/static" + BrowseConsentPolicy.DOCUMENT)) {
            assertNotNull(input); byte[] bytes = input.readAllBytes();
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), notice.get("documentSha256"));
            String html = new String(bytes, StandardCharsets.UTF_8);
            for (String text : new String[]{BrowseConsentPolicy.VERSION, "新加坡", "旧版本授权不会自动升级", "原始浏览时间", "当前不按30天自动删除", "撤回", "备份"}) assertTrue(html.contains(text), text);
        }
        assertThrows(UnsupportedOperationException.class, () -> notice.put("accountId", 1));
    }
}
