package dev.sccl.pierdolauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Path jarPath = Path.of("build", "libs", "pierdol-auth-paper-0.1.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jarPath));

        try (JarFile jar = new JarFile(jarPath.toFile());
             InputStream stream = jar.getInputStream(jar.getJarEntry("plugin.yml"))) {
            @SuppressWarnings("unchecked")
            var pluginYaml = (java.util.Map<String, Object>) new Yaml().load(stream);
            assertEquals("PierdolAuth", pluginYaml.get("name"));
            assertEquals("dev.sccl.pierdolauth.PierdolAuthPlugin", pluginYaml.get("main"));
            assertEquals("26.3", pluginYaml.get("api-version"));
            assertEquals("0.1.0-SNAPSHOT", pluginYaml.get("version"));
            assertTrue(((java.util.List<?>) pluginYaml.get("softdepend")).contains("FastLogin"));
            assertTrue(((java.util.Map<?, ?>) pluginYaml.get("commands")).containsKey("login"));
        }
    }
}
