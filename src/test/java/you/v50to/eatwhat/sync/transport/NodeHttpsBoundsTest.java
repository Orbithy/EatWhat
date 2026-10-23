package you.v50to.eatwhat.sync.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("JDK_HTTP_SERVER_CONFIGURATION")
class NodeHttpsBoundsTest {
    private static final List<String> KEYS = List.of("jdk.httpserver.maxConnections", "sun.net.httpserver.maxReqTime",
            "sun.net.httpserver.maxRspTime", "sun.net.httpserver.maxReqHeaders", "sun.net.httpserver.maxReqHeaderSize",
            "sun.net.httpserver.idleInterval", "sun.net.httpserver.clockTick", "sun.net.httpserver.timerMillis",
            "sun.net.httpserver.maxIdleConnections", "sun.net.httpserver.drainAmount");

    @Test
    void processWideBoundsPreserveStricterValuesAndConstrainUnlimitedOrLooseValues() {
        Map<String, String> original = snapshot();
        try {
            KEYS.forEach(System::clearProperty);
            System.setProperty("jdk.httpserver.maxConnections", "8");
            System.setProperty("sun.net.httpserver.maxReqTime", "-1");
            System.setProperty("sun.net.httpserver.maxRspTime", "600000");
            NodeHttpsServer.configureJdkBounds();
            assertEquals("8", System.getProperty("jdk.httpserver.maxConnections"));
            assertEquals("30", System.getProperty("sun.net.httpserver.maxReqTime"));
            assertEquals("30", System.getProperty("sun.net.httpserver.maxRspTime"));
            assertEquals("16384", System.getProperty("sun.net.httpserver.maxReqHeaderSize"));
            assertEquals("0", System.getProperty("sun.net.httpserver.drainAmount"));
            assertEquals("0", System.getProperty("sun.net.httpserver.maxIdleConnections"));
        } finally { restore(original); }
    }

    @Test
    void invalidPropertyTextFailsSafelyInsteadOfFallingBackToAnUnboundedJdkDefault() {
        Map<String, String> original = snapshot();
        try {
            KEYS.forEach(System::clearProperty);
            System.setProperty("jdk.httpserver.maxConnections", "invalid-value-sentinel");
            var failure = assertThrows(IllegalStateException.class, NodeHttpsServer::configureJdkBounds);
            assertEquals("Invalid JDK node listener resource bound", failure.getMessage());
            assertNull(failure.getCause());
        } finally { restore(original); }
    }

    private static Map<String, String> snapshot() {
        Map<String, String> values = new LinkedHashMap<>();
        KEYS.forEach(key -> values.put(key, System.getProperty(key)));
        return values;
    }

    private static void restore(Map<String, String> values) {
        values.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }
}
