package com.consense.config;

import java.nio.file.Paths;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.FilePathWrapper;

/** Opt-in workaround for the local Windows JDK's false NIO write-access check. */
public final class H2LocalFileAccess {
    private H2LocalFileAccess() {}

    public static void register() {
        // TCP clients spool large LOBs too; register before H2 reads its static properties.
        if (System.getProperty("h2.prefixTempFile") == null)
            System.setProperty("h2.prefixTempFile", "rwfile:h2.temp");
        FilePath.register(new WritablePath());
    }

    public static final class WritablePath extends FilePathWrapper {
        public WritablePath() {}
        @Override public String getScheme() { return "rwfile"; }
        @Override public boolean canWrite() { return Paths.get(getBase().toString()).toFile().canWrite(); }
    }
}
