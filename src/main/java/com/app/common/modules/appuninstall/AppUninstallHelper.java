package com.app.common.modules.appuninstall;

import javax.swing.*;

import com.app.common.definitions.AppDataPaths;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class AppUninstallHelper {

    private static final int SINGLE_INSTANCE_PORT = 54321;
    private static final File APP_DIR = new File(AppDataPaths.appDir());

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { // NOSONAR S108
        }

        if (isAppRunning()) {
            JOptionPane.showMessageDialog(
                    null,
                    "<html><b>BDMA đang chạy.</b><br>" +
                            "Vui lòng tắt ứng dụng trước khi gỡ cài đặt.</html>",
                    "BDMA đang chạy",
                    JOptionPane.WARNING_MESSAGE);
            System.exit(1);
        }

        if (!APP_DIR.exists()) {
            System.exit(0);
        }

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel(
                "<html>" +
                        "Bạn có muốn xóa toàn bộ dữ liệu của BDMA không?<br/>" +
                        "<small style='color:gray'>Bao gồm cài đặt, lịch sử và các file đã lưu tại:<br/>" +
                        APP_DIR.getAbsolutePath() + "</small>" +
                        "</html>"),
                BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                null,
                panel,
                "Gỡ cài đặt BDMA",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            deleteDirectory(APP_DIR);
        }

        System.exit(0);
    }

    private static boolean isAppRunning() {
        try (Socket socket = new Socket("127.0.0.1", SINGLE_INSTANCE_PORT)) { // fix S2095
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteDirectory(File dir) {
        if (!dir.exists()) {
            return;
        }

        try (var files = Files.walk(dir.toPath())) {
            files.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Không thể xóa dữ liệu:\n" + e.getMessage(),
                    "Lỗi",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}