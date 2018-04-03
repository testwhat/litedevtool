/*
 * Copyright (C) 2014 Riddle Hsu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rh.ldt.util;

import org.rh.ldt.DLog;
import org.rh.smaliex.MiscUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Collection;

public class FileUtil extends MiscUtil {

    public static Collection<String> readLineSepFile(String filename) {
        ArrayList<String> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(filename))) {
            while (r.ready()) {
                String line = r.readLine().trim();
                if (line.length() > 0) {
                    list.add(line);
                }
            }
        } catch (IOException ex) {
            DLog.ex(ex);
        }
        return list;
    }

    public static void writeTextFile(String filename, String data) {
        writeTextFile(new File(filename), data);
    }

    public static void writeTextFile(File file, String data) {
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
            out.write(data);
        } catch (IOException ex) {
            DLog.ex(ex);
        }
    }

    public static File appendTail(File f, String str) {
        String name = getFilenameNoExt(f.getName())
                + str + "." + getFilenameExt(f.getName());
        return new File(StringUtil.getFileDirPath(f.getAbsolutePath()), name);
    }

    public static File[] listFiles(File file) {
        File[] files = file.listFiles();
        return files == null ? new File[0] : files;
    }

    public static void copy(String srcFile, String destFile) throws IOException {
        copy(new File(srcFile), new File(destFile));
    }

    public static void copy(File srcFile, File destFile) throws IOException {
        try (FileInputStream fi = new FileInputStream(srcFile)) {
            try (FileOutputStream fo = new FileOutputStream(destFile)) {
                fi.getChannel().transferTo(0, fi.getChannel().size(), fo.getChannel());
            }
        }
    }

    public static void move(File src, File dest) {
        if (!src.renameTo(dest)) {
            DLog.i("Replace existing " + dest);
            try {
                java.nio.file.Files.move(src.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                DLog.ex(e);
            }
        }
    }

    public static void delete(File file) {
        if (!file.delete()) {
            DLog.i("Unable to delete " + file);
        }
    }

    public static void deleteFolder(File folder) {
        for (File f : listFiles(folder)) {
            if (f.isDirectory()) {
                deleteFolder(f);
            } else {
                delete(f);
            }
        }
        delete(folder);
    }

    public static void download(String url, String toPath) {
        try {
            URL website = new URL(url);
            ReadableByteChannel rbc = Channels.newChannel(website.openStream());
            FileOutputStream fos = new FileOutputStream(toPath);
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        } catch (IOException ex) {
            DLog.ex(ex);
        }
    }

    public interface Progress {
        void onProgress(int val);
    }

    public static void download(final String urlStr, final String toPath, final Progress p) {
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) (url.openConnection());
                long fileSize = conn.getContentLength();
                try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream())) {
                    FileOutputStream fos = new FileOutputStream(toPath);
                    try (BufferedOutputStream bout = new BufferedOutputStream(fos, 1024)) {
                        byte[] data = new byte[4096];
                        long downloadedSize = 0;
                        int readBytes;
                        while ((readBytes = in.read(data, 0, data.length)) >= 0) {
                            downloadedSize += readBytes;
                            float perc = (float) downloadedSize / (float) fileSize;
                            final int currentProgress = (int) (perc * 100000d);
                            p.onProgress(currentProgress);
                            bout.write(data, 0, readBytes);
                        }
                    }
                }
            } catch (IOException e) {
                DLog.ex(e);
            }
        }).start();
    }

}
