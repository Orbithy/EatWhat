package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.EventCodec;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The Python importer and Java endpoint must hash the same canonical request bytes. */
class CatalogImportCanonicalCompatibilityTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void pythonImporterCanonicalNumbersUnicodeAndBoundaryJsonMatchEventCodec() throws Exception {
        String input = """
                {"hubs":[{"name":"济南中心","centerWgs":{"lng":117.0,"lat":36.0},
                "centerGcj":{"lng":1e-7,"lat":-0.0},
                "boundaryGeoJson":"{\\"type\\":\\"Polygon\\",\\"coordinates\\":[[[117.0,36.0],[117.2,36.0],[117.2,36.2],[117.0,36.0]]]}"}]}
                """;
        Process process = new ProcessBuilder("python3", "-c", """
                import hashlib, importlib.util, json, pathlib, sys
                from pathlib import Path
                from urllib.parse import urlparse
                root = Path.cwd()
                spec = importlib.util.spec_from_file_location('hub_import', root / 'scripts/import_hubs_from_geojson.py')
                module = importlib.util.module_from_spec(spec)
                sys.modules[spec.name] = module
                spec.loader.exec_module(module)
                request = json.load(sys.stdin)
                for hub in request['hubs']:
                    hub['boundaryGeoJson'] = module.canonical_json(json.loads(hub['boundaryGeoJson'])).decode('utf-8')
                canonical = module.canonical_json(request['hubs'])
                print(canonical.decode('utf-8'))
                print(hashlib.sha256(canonical).hexdigest())
                """).directory(Path.of(System.getProperty("user.dir")).toFile()).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "python importer must exit promptly");
        assertEquals(0, process.exitValue(), errors);
        String[] lines = output.split("\\R");
        assertEquals(2, lines.length, output);

        String javaCanonical = EventCodec.canonicalJson(lines[0]);
        assertEquals(lines[0], javaCanonical);
        assertEquals(lines[1], EventCodec.sha256(javaCanonical));

        java.util.List<?> decoded = JSON.readValue(javaCanonical, java.util.List.class);
        assertEquals("济南中心", ((Map<?, ?>) decoded.getFirst()).get("name"));
        assertTrue(javaCanonical.contains("\"lng\":0.0000001"));
        assertTrue(javaCanonical.contains("\"lat\":0"), "Negative zero canonicalizes to zero on both runtimes");
        assertTrue(javaCanonical.contains("117,36"), "Boundary geometry is canonicalized before request hashing");
    }
}
