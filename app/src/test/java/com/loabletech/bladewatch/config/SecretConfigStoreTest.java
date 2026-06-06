package com.loabletech.bladewatch.config;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public class SecretConfigStoreTest {

    private Path tempDir;
    private File storeFile;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("secret-config-store-test");
        storeFile = tempDir.resolve("secrets.json").toFile();
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursive(tempDir.toFile());
    }

    @Test
    public void writesReadsAndDeletesValues() {
        SecretConfigStore store = new SecretConfigStore(storeFile);

        Assert.assertTrue(store.putString("auth", "deviceSecret", "secret-123"));
        Assert.assertEquals("secret-123", store.getString("auth", "deviceSecret"));

        Assert.assertTrue(store.delete("auth", "deviceSecret"));
        Assert.assertNull(store.getString("auth", "deviceSecret"));
    }

    @Test
    public void missingFileLoadsAsEmpty() {
        SecretConfigStore store = new SecretConfigStore(storeFile);

        Assert.assertFalse(store.exists());
        Assert.assertNull(store.getString("zrok", "token"));
        Assert.assertNotNull(store.loadSection("zrok"));
    }

    @Test
    public void invalidJsonIsRecoveredOnWrite() throws Exception {
        Files.write(storeFile.toPath(), "{not-json".getBytes(StandardCharsets.UTF_8));

        SecretConfigStore store = new SecretConfigStore(storeFile);
        Assert.assertNull(store.getString("zrok", "token"));
        Assert.assertTrue(store.putString("zrok", "token", "zrok-secret"));
        Assert.assertEquals("zrok-secret", store.getString("zrok", "token"));
    }

    @Test
    public void permissionsRemainOwnerOnly() throws Exception {
        SecretConfigStore store = new SecretConfigStore(storeFile);
        Assert.assertTrue(store.putString("zrok", "enableToken", "enable-secret"));

        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(storeFile.toPath());
        Assert.assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        Assert.assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        Assert.assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        Assert.assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE));
        Assert.assertFalse(perms.contains(PosixFilePermission.GROUP_EXECUTE));
        Assert.assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
        Assert.assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
        Assert.assertFalse(perms.contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
