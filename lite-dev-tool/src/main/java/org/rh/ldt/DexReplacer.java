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

import org.jf.dexlib2.MultiDex;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.rh.ldt.util.DexUtilEx;
import org.rh.ldt.util.FileUtil;
import org.rh.smaliex.MiscUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public class DexReplacer {
    public final static String OUTPUT_FOLDER = "output-jar";
    public final static String DEFAULT_CONFIG = "replace-config.txt";
    public final static String PREVIOUS_JAR_POSTFIX = "-previous";

    public static void main(String[] args) {
        if (args == null || args.length < 1) {
            File dc = new File(System.getProperty("user.dir"), DEFAULT_CONFIG);
            if (!dc.exists()) {
                printUsage();
                return;
            }
            args = new String[]{dc.getAbsolutePath()};
        }
        execReplace(args[0]);
    }

    public final static String SAMPLE = "[patch.dex:framework.jar]"
            + "\nandroid/app/ActivityManager.java"
            + "\nandroid.os.*"
            + "\n[patch.dex:services.jar]"
            + "\ncom.android.server.SystemServer"
            + "\n# comment com/android/server/Test.java"
            + "\n# package path . and / are equivalent, extension .java is optional";

    static void printUsage() {
        System.out.println("Usage: <replace-config-file>");
        System.out.println(" e.g. java -jar me.jar " + DEFAULT_CONFIG + "\n");
        System.out.println("Example of replace-config.txt");
        System.out.println("----------------------------------");
        System.out.println(SAMPLE);
        System.out.println("----------------------------------");
    }

    public static void execReplace(String configFile) {
        File rc = new File(configFile);
        execReplace(new ReplaceInfo(rc), rc.getParentFile());
    }

    public static File execReplace(ReplaceInfo info, File baseFolder) {
        File outputFolder = new File(baseFolder, OUTPUT_FOLDER);
        FileUtil.mkdirs(outputFolder);

        for (String replaceCmd : info.keySet()) {
            int sepPos = replaceCmd.indexOf(':');
            if (sepPos < 0) {
                DLog.e("Invalid command: " + replaceCmd);
                continue;
            }
            String patch = replaceCmd.substring(0, sepPos);
            String input = replaceCmd.substring(sepPos + 1);
            File patchFile = new File(baseFolder, patch);
            File inputFile = new File(baseFolder, input);

            List<DexBackedDexFile> patchF = DexUtilEx.loadMultiDex(patchFile);
            List<DexBackedDexFile> inputF = DexUtilEx.loadMultiDex(inputFile);
            DexBackedClassReplacer result = new DexBackedClassReplacer(
                    inputF, patchF, info.get(replaceCmd));
            if (result.mReplacerClasses.isEmpty()) {
                DLog.i("Nothing to replace from " + patchFile);
                continue;
            }
            MultiDex multiDex = new MultiDex(result.opcode);
            multiDex.classes.addAll(result.getClasses());
            DLog.i("Preparing dex data from " + input);
            List<MemoryDataStore> dexData = multiDex.asMemory();

            File outJar = new File(outputFolder, inputFile.getName());
            if (outJar.exists() && outJar.equals(inputFile)) {
                inputFile = FileUtil.appendTail(outJar, PREVIOUS_JAR_POSTFIX);
                FileUtil.move(outJar, inputFile);
                DLog.i("Input is the same as output, rename to " + inputFile);
            }
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outJar))) {
                for (int i = 0; i < dexData.size(); i++) {
                    jos.putNextEntry(new ZipEntry(MultiDex.getDexFileName(i)));
                    dexData.get(i).writeTo(jos);
                    jos.closeEntry();
                }

                // Copy remain files (exclude dex) from original jar.
                try (JarFile jarFile = new JarFile(inputFile)) {
                    final Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        final JarEntry e = entries.nextElement();
                        String name = e.getName();
                        if (name.startsWith("classes") && name.endsWith(".dex")) {
                            continue;
                        }
                        jos.putNextEntry(new ZipEntry(name));
                        try (InputStream is = jarFile.getInputStream(e)) {
                            jos.write(MiscUtil.readBytes(is));
                        }
                        jos.closeEntry();
                    }
                }
            } catch (IOException e) {
                DLog.ex(e);
            }
            DLog.i("Output: " + outJar);
        }
        return outputFolder;
    }

    // dexFile -> java filenames
    //[patch.dex:framework.jar]
    //android/app/Activity.java
    //[patch.dex:services.jar]
    //com/android/server/Watchdog.java

    // TODO Support delete class "-com/android/server/ABC.java"
    public static final class ReplaceInfo extends LinkedHashMap<String, HashSet<String>> {

        public ReplaceInfo() {
        }

        public ReplaceInfo(File file) {
            try {
                read(new FileReader(file));
            } catch (IOException ex) {
                DLog.ex(ex);
            }
        }

        public void read(Reader reader) {
            HashSet<String> srcNames = null;
            try (BufferedReader r = new BufferedReader(reader)) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() < 2 || line.charAt(0) == '#') {
                        continue;
                    }
                    if (line.startsWith("[") && line.endsWith("]")) {
                        String cmd = line.substring(1, line.length() - 1);
                        srcNames = getTarget(cmd);
                    } else if (srcNames != null) {
                        srcNames.add(formatName(line));
                    }
                }
            } catch (IOException ex) {
                DLog.ex(ex);
            }
        }

        public HashSet<String> getTarget(String cmd) {
            HashSet<String> srcNames = get(cmd);
            if (srcNames == null) {
                srcNames = new HashSet<>();
                put(cmd, srcNames);
            }
            return srcNames;
        }

        public void addTarget(String cmd, String name) {
            getTarget(cmd).add(formatName(name));
        }

        static String formatName(String name) {
            // Append .java to have the same pattern as ClassDef.getSourceFile
            if (!name.endsWith(".java")) {
                return name.replace(".", "/") + ".java";
            }
            return name;
        }

        public void saveTo(File configFile) {
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(toString());
            } catch (IOException ex) {
                DLog.ex(ex);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            for (String cmd : keySet()) {
                sb.append("[").append(cmd).append("]\n");
                for (String f : get(cmd)) {
                    sb.append(f).append("\n");
                }
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof ReplaceInfo) {
                ReplaceInfo info = (ReplaceInfo) o;
                for (String cmd : keySet()) {
                    if (!get(cmd).equals(info.getTarget(cmd))) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        public boolean isAllEmpty() {
            for (HashSet<String> fs : values()) {
                if (!fs.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    public static class DexBackedClassReplacer implements DexFile {

        protected final HashSet<DexBackedClassDef> mSrcClasses = new HashSet<>();
        // Its class order should be the same as src classes.
        protected final HashSet<DexBackedClassDef> mReplacerClasses = new LinkedHashSet<>();
        protected final HashSet<String> mReplaceSrcNames = new HashSet<>();
        public final Opcodes opcode;

        public DexBackedClassReplacer(@Nonnull List<DexBackedDexFile> srcDexFiles,
                @Nonnull List<DexBackedDexFile> replacerDexFiles,
                @Nullable java.util.Collection<String> targetSourceNames) {
            opcode = srcDexFiles.isEmpty() ? DexUtilEx.getDefaultOpCodes()
                    : srcDexFiles.get(0).getOpcodes();
            for (DexBackedDexFile d : srcDexFiles) {
                mSrcClasses.addAll(d.getClasses());
            }
            if (targetSourceNames == null) { // Replace all
                for (DexBackedDexFile d : replacerDexFiles) {
                    mReplacerClasses.addAll(d.getClasses());
                }
                for (ClassDef c : mReplacerClasses) {
                    // Activity.java to android/app/Activity.java
                    mReplaceSrcNames.add(DexUtilEx.classToSourceName(c));
                }
            } else { // Replace by condition
                ArrayList<String> regexpMatchedNames = new ArrayList<>();
                ArrayList<Pattern> patterns = new ArrayList<>();

                for (String name : targetSourceNames) {
                    if (name.contains("*")) {
                        patterns.add(Pattern.compile(name.replace("*", ".*")));
                    } else {
                        mReplaceSrcNames.add(name);
                    }
                }

                boolean containsPattern = !patterns.isEmpty();
                for (DexBackedDexFile d : replacerDexFiles) {
                    for (DexBackedClassDef c : d.getClasses()) {
                        String srcName = DexUtilEx.classToSourceName(c);
                        if (c.getSourceFile() == null) {
                            DLog.v("No source type: " + c.getType());
                        }
                        if (mReplaceSrcNames.contains(srcName)) {
                            mReplacerClasses.add(c);
                        } else if (containsPattern) {
                            for (Pattern p : patterns) {
                                if (p.matcher(srcName).matches()) {
                                    regexpMatchedNames.add(srcName);
                                    mReplacerClasses.add(c);
                                    break;
                                }
                            }
                        }
                    }
                }
                mReplaceSrcNames.addAll(regexpMatchedNames);
            }
        }

        public DexBackedClassReplacer(@Nonnull List<DexBackedDexFile> srcDexFiles,
                @Nonnull List<DexBackedDexFile> replacerDexFiles) {
            this(srcDexFiles, replacerDexFiles, null);
        }

        @Nonnull
        @Override
        public java.util.Set<DexBackedClassDef> getClasses() {
            if (mReplacerClasses.isEmpty()) {
                DLog.i("Empty replacer classes.");
                return mSrcClasses;
            }
            return new AbstractSet<DexBackedClassDef>() {
                boolean mStartReplacer;

                @Nonnull
                @Override
                public Iterator<DexBackedClassDef> iterator() {
                    final Iterator<DexBackedClassDef> sIter = mSrcClasses.iterator();
                    final Iterator<DexBackedClassDef> rIter = mReplacerClasses.iterator();
                    final HashSet<String> replacedFiles = new HashSet<>();
                    return new Iterator<DexBackedClassDef>() {
                        @Override
                        public boolean hasNext() {
                            if (!mStartReplacer) {
                                if (!sIter.hasNext()) {
                                    mStartReplacer = true;
                                }
                                return true;
                            }
                            return rIter.hasNext();
                        }

                        @Override
                        public DexBackedClassDef next() {
                            DexBackedClassDef classDef;
                            if (!mStartReplacer && sIter.hasNext()) {
                                // Go through all non-replace classes
                                classDef = sIter.next();
                                while (mReplaceSrcNames.contains(
                                        DexUtilEx.classToSourceName(classDef))) {
                                    if (!sIter.hasNext()) {
                                        mStartReplacer = true;
                                        break;
                                    }
                                    // Skip source classes which will be replaced
                                    classDef = sIter.next();
                                }
                            } else {
                                // The remain classes are from replacer
                                classDef = rIter.next();
                                String srcName = classDef.getSourceFile();
                                if (srcName == null) {
                                    DLog.v("Add " + classDef.getType()
                                            + " @ " + DexUtilEx.classToSourceName(classDef));
                                } else if (!replacedFiles.contains(srcName)) {
                                    replacedFiles.add(srcName);
                                    DLog.i("Replace " + srcName);
                                }
                            }
                            return classDef;
                        }

                        @Override
                        public void remove() {
                            throw new UnsupportedOperationException();
                        }
                    };
                }

                @Override
                public int size() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Nonnull
        @Override
        public Opcodes getOpcodes() {
            return opcode;
        }
    }
}
