package dev.sccl.pierdolauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

final class PluginMetadataTest {
    @Test
    void packagedPluginMetadataIsCompleteAndSecretFree() throws Exception {
        String expectedVersion = System.getProperty("pierdolAuthVersion");
        assertNotNull(expectedVersion, "Gradle must independently supply the expected project version");
        Path jarPath = Path.of(System.getProperty("pierdolAuthJar"));
        assertTrue(Files.isRegularFile(jarPath));
        assertEquals("pierdol-auth-paper-" + expectedVersion + ".jar", jarPath.getFileName().toString());

        try (JarFile jar = new JarFile(jarPath.toFile());
             InputStream stream = jar.getInputStream(jar.getJarEntry("plugin.yml"))) {
            @SuppressWarnings("unchecked")
            var pluginYaml = (java.util.Map<String, Object>) new Yaml().load(stream);
            assertEquals("PierdolAuth", pluginYaml.get("name"));
            assertEquals("dev.sccl.pierdolauth.PierdolAuthPlugin", pluginYaml.get("main"));
            assertEquals("26.3", pluginYaml.get("api-version"));
            assertEquals(expectedVersion, pluginYaml.get("version"));
            assertEquals(expectedVersion, jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
            assertTrue(((java.util.List<?>) pluginYaml.get("softdepend")).contains("FastLogin"));
            assertTrue(((java.util.Map<?, ?>) pluginYaml.get("commands")).containsKey("login"));
        }
    }
}
