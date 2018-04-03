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

import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.rh.ldt.util.DexUtilEx;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class DexDiff {

    public static class Param {
        public String dexF1;
        public String dexF2;
        public Writer w1;
        public Writer w2;
        public String[] keywords;
        public boolean outputFilename;
    }

    public static void diff(Param param) throws IOException {
        long s = System.currentTimeMillis();
        HashMap<String, List<Method>> cms1 = new HashMap<>();
        for (DexBackedDexFile df : DexUtilEx.loadMultiDex(new File(param.dexF1))) {
            collectMethodsByKeyword(df, param.keywords, cms1);
        }
        HashMap<String, List<Method>> cms2 = new HashMap<>();
        for (DexBackedDexFile df : DexUtilEx.loadMultiDex(new File(param.dexF2))) {
            collectMethodsByKeyword(df, param.keywords, cms2);
        }

        HashSet<String> matchedSrcFiles = new HashSet<>();
        matchedSrcFiles.addAll(cms1.keySet());
        matchedSrcFiles.addAll(cms2.keySet());

        // dexFile -> className -> remain methods
        ArrayList<HashMap<String, ArrayList<Method>>> diffResult1 = new ArrayList<>();
        ArrayList<HashMap<String, ArrayList<Method>>> diffResult2 = new ArrayList<>();
        for (String srcName : matchedSrcFiles) {
            List<Method> ms1 = cms1.get(srcName);
            List<Method> ms2 = cms2.get(srcName);
            if (ms1 == null || ms2 == null) {
                continue;
            }
            Iterator<Method> m1i = ms1.iterator();
            while (m1i.hasNext()) {
                Method m1 = m1i.next();
                Iterator<Method> m2i = ms2.iterator();
                while (m2i.hasNext()) {
                    Method m2 = m2i.next();
                    if (DexUtilEx.isSameMethod(m1, m2)) {
                        m1i.remove();
                        m2i.remove();
                        break;
                    }
                }
            }
            if (ms1.isEmpty()) {
                cms1.remove(srcName);
            }
            if (ms2.isEmpty()) {
                cms2.remove(srcName);
            }
            if (param.w1 != null) {
                diffResult1.add(arrangeMethodByClass(ms1));
            }
            if (param.w2 != null) {
                diffResult2.add(arrangeMethodByClass(ms2));
            }
        }
        TreeSet<String> diffFiles = new TreeSet<>();
        diffFiles.addAll(cms1.keySet());
        diffFiles.addAll(cms2.keySet());
        if (param.w1 != null) {
            write(param, param.w1, diffFiles, diffResult1);
        }
        if (param.w2 != null) {
            write(param, param.w2, diffFiles, diffResult2);
        }
        DLog.i("DexMethodDiff cost " + (System.currentTimeMillis() - s) + " ms");
    }

    static void write(Param param, Writer w, Set<String> diffFiles,
            ArrayList<HashMap<String, ArrayList<Method>>> diffResult) throws IOException {
        StringBuilder sb = new StringBuilder();
        w.append("Outputting methods existed in ").append(param.dexF1)
                .append(" but not existed in ").append(param.dexF2).append("\n\n");
        if (param.keywords != null && param.keywords.length > 0
                && param.keywords[0].length() > 0) {
            w.append("Filename keyword filter:\n");
            for (String tf : param.keywords) {
                w.append(tf).append(" ");
            }
            w.append("\n\n");
        }

        if (param.outputFilename) {
            w.append("Files with method differences:\n");
            //int c = 0;
            for (String fn : diffFiles) {
                w.append(fn).append("\n");
                //if (++c % 3 == 0) {
                //    w.append("\n");
                //}
            }
            w.append("\n\n");
        }

        for (HashMap<String, ArrayList<Method>> remainMethods : diffResult) {
            for (String clsName : remainMethods.keySet()) {
                w.append(DexUtilEx.toReadableType(clsName)).append("\n");
                for (Method m : remainMethods.get(clsName)) {
                    w.append("\t").append(DexUtilEx.getMethodString(m, sb)).append("\n");
                }
                w.append("\n");
            }
        }
    }

    public static HashMap<String, ArrayList<Method>> arrangeMethodByClass(List<Method> ms) {
        HashMap<String, ArrayList<Method>> arrangeByClass = new HashMap<>();
        for (Method m : ms) {
            if (DexUtilEx.isConstructor(m)) {
                continue;
            }
            String cn = m.getDefiningClass();
            arrangeByClass.computeIfAbsent(cn, k -> new ArrayList<>()).add(m);
        }
        return arrangeByClass;
    }

    public static void collectMethodsByKeyword(DexFile df,
            String[] keywords, HashMap<String, List<Method>> cms) {
        for (ClassDef c : df.getClasses()) {
            String srcFilename = c.getSourceFile();
            if (srcFilename == null) {
                DLog.i("Null src name " + c.getType());
                continue;
            }
            String clsName = DexUtilEx.toReadableType(c.getType());
            int lastDot = clsName.lastIndexOf('.');
            if (lastDot > 0) {
                String pkg = clsName.substring(0, lastDot + 1);
                srcFilename = pkg + srcFilename;
            }
            //LLog.i(srcFilename);

            if (keywords != null) {
                boolean found = false;
                for (String kw : keywords) {
                    if (srcFilename.contains(kw)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    continue;
                }
            }

            List<Method> ms = cms.computeIfAbsent(srcFilename, k -> new LinkedList<>());
            for (Method m : c.getMethods()) {
                if (DexUtilEx.isSyntheticMethod(m)) {
                    continue;
                }
                ms.add(m);
            }
        }
    }
}
