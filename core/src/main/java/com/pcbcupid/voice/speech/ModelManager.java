package com.pcbcupid.voice.speech;

import java.io.File;
import java.io.InputStream;

public interface ModelManager {
    boolean isInstalled();
    File modelDirectory();
    void install(InputStream zip) throws Exception;
    default void install(InputStream zip, java.util.function.IntConsumer progress) throws Exception {
        install(zip);
        progress.accept(100);
    }
}
