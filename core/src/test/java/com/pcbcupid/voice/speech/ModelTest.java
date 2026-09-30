package com.pcbcupid.voice.speech;

import java.io.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ModelTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void missingAndInvalidModelsAreRejectedWithoutLeavingFiles() throws Exception {
        LocalModelManager manager = new LocalModelManager(temp.newFolder());
        assertFalse(manager.isInstalled());
        assertThrows(IOException.class, () -> manager.install(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        assertFalse(manager.isInstalled());
        assertEquals(0, manager.modelDirectory().getParentFile().list().length);
    }
    @Test public void partialDirectoryIsNotConsideredInstalled() throws Exception {
        LocalModelManager manager = new LocalModelManager(temp.newFolder());
        assertTrue(manager.modelDirectory().mkdirs());
        Files.write(new File(manager.modelDirectory(), ".complete").toPath(), new byte[0]);
        assertFalse(manager.isInstalled());
    }
}
