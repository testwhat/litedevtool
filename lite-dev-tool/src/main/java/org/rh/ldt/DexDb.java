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

package org.rh.ldt;

import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.rh.ldt.util.DexUtilEx;
import org.rh.ldt.util.FileUtil;
import org.rh.smaliex.DexUtil;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DexDb implements java.io.Closeable {
    static {
        java.util.logging.Logger.getLogger("com.almworks.sqlite4java").setLevel(
                java.util.logging.Level.OFF);
    }

    private final SQLiteConnection mConn;
    private final SQLiteStatement mInsertJar;
    private final SQLiteStatement mInsertType;

    private final SQLiteStatement mSelJid;
    private final SQLiteStatement mSelJarName;
    private int mApiLevel;

    public DexDb(String folder) throws SQLiteException {
        this(folder, false);
    }

    public DexDb(String folder, boolean init) throws SQLiteException {
        mConn = new SQLiteConnection(getDbFile(folder)).open(true);
        if (init) {
            mConn.exec("DROP TABLE IF EXISTS jars;");
            mConn.exec("DROP TABLE IF EXISTS classes;");
        }
        mConn.exec("CREATE TABLE IF NOT EXISTS jars (id INTEGER PRIMARY KEY, jname TEXT, dname TEXT);");
        mConn.exec("CREATE TABLE IF NOT EXISTS classes (cname TEXT PRIMARY KEY, jid INTEGER);");

        mInsertJar = mConn.prepare("INSERT INTO jars VALUES (?, ?, ?);");
        mInsertType = mConn.prepare("INSERT INTO classes VALUES (?, ?);");

        mSelJid = mConn.prepare("SELECT jid FROM classes WHERE cname LIKE ?;");
        mSelJarName = mConn.prepare("SELECT jname, dname FROM jars WHERE id=?;");
    }

    public void setApiLevel(int apiLevel) {
        mApiLevel = apiLevel;
    }

    public void addClassesToDb(File[] dexFiles) throws SQLiteException {
        int jarFileId = 1;
        SQLiteStatement stmt = mConn.prepare("SELECT max(id) FROM jars;");
        if (stmt.step()) {
            jarFileId = stmt.columnInt(0) + 1;
        }
        final Opcodes opcodes = mApiLevel > 0 ? DexUtil.getOpcodes(mApiLevel) : null;
        for (File f : dexFiles) {
            if (!f.exists()) {
                DLog.i("addClassesToDb: " + f + " does not exist");
                continue;
            }
            DLog.i("Loading " + f.getName() + " to DB");
            mConn.exec("BEGIN TRANSACTION;");
            try (ZipFile zipFile = new ZipFile(f)) {
                Enumeration<? extends ZipEntry> zs = zipFile.entries();
                while (zs.hasMoreElements()) {
                    ZipEntry entry = zs.nextElement();
                    String name = entry.getName();
                    int fileSize = (int) entry.getSize();
                    if (name.startsWith("classes") && name.endsWith(".dex")
                            && fileSize > 40) {
                        insertJar(jarFileId, f.getName(), name);
                        DexBackedDexFile df = new DexBackedDexFile(opcodes,
                                FileUtil.readBytes(zipFile.getInputStream(entry)), 0);
                        processDex(df, jarFileId);
                        jarFileId++;
                    }
                }
            } catch (IOException e) {
                DLog.ex(e);
            }
            mConn.exec("COMMIT TRANSACTION;");
        }
    }

    private void processDex(DexBackedDexFile df, int jid) throws SQLiteException {
        for (ClassDef cls : df.getClasses()) {
            String type = cls.getType(); // type: Landroid/app/Activity;
            try {
                insertType(type, jid);
            } catch (SQLiteException e) {
                // TODO duplicated class contained in different dex
                String err = mConn.getErrorMessage();
                mInsertType.reset();
                SQLiteStatement stmt = mConn.prepare(
                        "SELECT jname FROM jars WHERE id=" + jid);
                if (stmt.step()) {
                    String ef = stmt.columnString(0);
                    stmt.dispose();
                    String ej = locateType(type).jar;
                    DLog.i("[" + err + "] " + ef + " has " + type
                            + " but already added in " + ej);
                } else {
                    DLog.i("[" + err + "] " + e);
                }
            }
        }
    }

    public void insertJar(int id, String jarName, String dexName) throws SQLiteException {
        mInsertJar.bind(1, id);
        mInsertJar.bind(2, jarName);
        mInsertJar.bind(3, dexName);
        mInsertJar.stepThrough().reset();
    }

    public void insertType(String type, int jid) throws SQLiteException {
        mInsertType.bind(1, type);
        mInsertType.bind(2, jid);
        mInsertType.stepThrough().reset();
    }

    // Sample of type: Landroid/os/PowerManager;
    public String getTargetJarForType(String type) {
        String jname = null;
        try {
            try {
                mSelJid.bind(1, type).step();
                if (mSelJid.hasRow()) {
                    int jid = mSelJid.columnInt(0);
                    mSelJarName.bind(1, jid).step();
                    jname = mSelJarName.columnString(0);
                    mSelJarName.reset();
                }
            } finally {
                mSelJid.reset();
            }
        } catch (SQLiteException ex) {
            DLog.e("Failed to find " + type + " ex:" + ex.getMessage());
        }
        return jname;
    }

    public String findTargetJarForType(String type) {
        String jarName;
        boolean sub = false;
        int i = 0;
        do {
            jarName = getTargetJarForType(type);
            if (jarName != null) {
                if (i > 2) {
                    DLog.i("findTargetJarForType: rough result: " + jarName
                            + " may not be the correct path for " + type);
                }
                return jarName;
            }
            if (!sub) {
                int p = type.lastIndexOf('$');
                if (p > 0) {
                    sub = true;
                    type = type.substring(0, p) + "%";
                    continue;
                }
            }
            int pathPos = type.lastIndexOf('/', type.length() - (i > 0 ? 3 : 2));
            if (pathPos > 0) {
                type = type.substring(0, pathPos + 1) + "%";
            } else {
                break;
            }
            i++;
        } while (true);
        return null;
    }

    public static class ClassLocation {
        public final String jar;
        public final String dex;
        public ClassLocation(String j, String d) {
            jar = j;
            dex = d;
        }

        @Override
        public String toString() {
            return "classes.dex".equals(dex) ? jar : (jar + ":" + dex);
        }
    }

    public ClassLocation locateType(String type) throws SQLiteException {
        try {
            mSelJid.bind(1, type).step();
            if (mSelJid.hasRow()) {
                int jid = mSelJid.columnInt(0);
                mSelJarName.bind(1, jid).step();
                String jarName = mSelJarName.columnString(0);
                String dexName = mSelJarName.columnString(1);
                mSelJarName.reset();
                return new ClassLocation(jarName, dexName);
            }
        } finally {
            mSelJid.reset();
        }
        return null;
    }

    public LinkedHashMap<String, ClassLocation> matchType(String type) {
        LinkedHashMap<String, ClassLocation> result = new LinkedHashMap<>();
        try {
            SQLiteStatement st = mConn.prepare(
                    "SELECT cname, jname, dname FROM classes LEFT JOIN jars" +
                            " WHERE classes.jid=jars.id AND cname LIKE ?;");
            try {
                st.bind(1, type);
                while (st.step()) {
                    result.put(st.columnString(0),
                            new ClassLocation(st.columnString(1), st.columnString(2)));
                }
            } finally {
                st.dispose();
            }
        } catch (SQLiteException ex) {
            DLog.e("Failed to find " + type + " ex:" + ex.getMessage());
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        mInsertJar.dispose();
        mInsertType.dispose();
        mSelJid.dispose();
        mSelJarName.dispose();
        mConn.dispose();
    }

    public File getDbFile() {
        return mConn != null ? mConn.getDatabaseFile() : null;
    }

    public static File getDbFile(String folderPath) {
        return new File(folderPath, "class-info.sqlite");
    }
}
