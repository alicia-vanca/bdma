package com.app.common.config;

public class AppConfig {

    private Storage storage = new Storage();
    private BodyCam bodyCam = new BodyCam();

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public BodyCam getBodyCam() {
        return bodyCam;
    }

    public void setBodyCam(BodyCam bodyCam) {
        this.bodyCam = bodyCam;
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    public static class Storage {
        private String dataDir = "";
        private String backupDir = "";

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        public String getBackupDir() {
            return backupDir;
        }

        public void setBackupDir(String backupDir) {
            this.backupDir = backupDir;
        }
    }

    // ── BodyCam ───────────────────────────────────────────────────────────────

    public static class BodyCam {
        private boolean autoDelete = false;

        public boolean isAutoDelete() {
            return autoDelete;
        }

        public void setAutoDelete(boolean autoDelete) {
            this.autoDelete = autoDelete;
        }
    }
}