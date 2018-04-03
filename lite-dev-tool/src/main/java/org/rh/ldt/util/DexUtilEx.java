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

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.util.MethodUtil;
import org.jf.dexlib2.util.TypeUtils;
import org.jf.dexlib2.writer.pool.DexPool;
import org.rh.ldt.DLog;
import org.rh.smaliex.DexUtil;
import org.rh.smaliex.LLog;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.AbstractSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DexUtilEx extends DexUtil {

    @Nonnull
    public static Opcodes getDefaultOpCodes() {
        return getDefaultOpCodes(null);
    }

    @Nonnull
    public static List<DexBackedDexFile> loadMultiDex(@Nonnull File f) {
        return DexUtil.loadMultiDex(f, null);
    }

    @Nullable
    public static DexBackedDexFile loadDex(@Nonnull File f) {
        try {
            return loadSingleDex(f);
        } catch (IOException ex) {
            DLog.ex(ex);
        }
        return null;
    }

    public static DexBackedDexFile loadSingleDex(@Nonnull File file) throws IOException {
        return DexUtil.loadSingleDex(file, null);
    }

    @Nonnull
    public static String getMethodString(@Nonnull Method m, @Nonnull StringBuilder sb) {
        sb.setLength(0);
        for (MethodParameter param : m.getParameters()) {
            String paramName = param.getName();
            if (paramName == null) {
                paramName = TypeUtils.isPrimitiveType(param.getType()) ? "val" : "obj";
            }
            sb.append(toReadableType(param.getType()))
                    .append(" ").append(paramName).append(", ");
        }
        if (sb.length() > 1) {
            sb.setLength(sb.length() - 2);
        }
        String paramStr = sb.toString();
        sb.setLength(0);
        String accStr = AccessFlags.formatAccessFlagsForMethod(m.getAccessFlags());
        return sb.append(accStr).append((accStr.length() > 0 ? " " : ""))
                .append(toReadableType(m.getReturnType())).append(" ").append(m.getName())
                .append("(").append(paramStr).append(")").toString();
    }

    public static boolean isSyntheticMethod(@Nonnull Method m) {
        return (m.getAccessFlags() & AccessFlags.SYNTHETIC.getValue()) != 0;
    }

    public static boolean isConstructor(@Nonnull Method m) {
        return (m.getAccessFlags() & AccessFlags.CONSTRUCTOR.getValue()) != 0;
    }

    public static boolean isSameMethod(Method m1, Method m2) {
        if (m1.getName().equals(m2.getName())
                && m1.getReturnType().equals(m2.getReturnType())
                && m1.getAccessFlags() == m2.getAccessFlags()
                && m1.getDefiningClass().equals(m2.getDefiningClass())) {
            Iterator<? extends MethodParameter> p1i = m1.getParameters().iterator();
            Iterator<? extends MethodParameter> p2i = m2.getParameters().iterator();
            while (p1i.hasNext()) {
                MethodParameter p1 = p1i.next();
                if (p2i.hasNext()) {
                    MethodParameter p2 = p2i.next();
                    if (!p1.getType().equals(p2.getType())) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            if (!p1i.hasNext() && !p2i.hasNext()) {
                return true;
            }
        }
        return false;
    }

    public static void writeDexFile(@Nonnull String path, @Nonnull DexFile dexFile) {
        try {
            DexPool.writeTo(path, dexFile);
        } catch (IOException ex) {
            DLog.ex(ex);
        }
    }

    public static boolean containsDex(@Nonnull File file) {
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> zs = zipFile.entries();
            while (zs.hasMoreElements()) {
                ZipEntry entry = zs.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    return true;
                }
            }

        } catch (IOException e) {
            DLog.ex(e);
        }
        return false;
    }

    public static class SingleClassReplacer implements DexFile {
        final DexFile srcDexFile;
        final Map<String, ClassDef> replacerClasses;

        public SingleClassReplacer(@Nonnull DexFile inputSrcDexFile) {
            srcDexFile = inputSrcDexFile;
            replacerClasses = new HashMap<>();
        }

        public void addReplaceClass(@Nonnull ClassDef cls) {
            replacerClasses.put(cls.getType(), cls);
        }

        @Nonnull
        @Override
        public Set<? extends ClassDef> getClasses() {
            return new AbstractSet<ClassDef>() {

                @Nonnull
                @Override
                public Iterator<ClassDef> iterator() {
                    final Iterator<? extends ClassDef> sIter = srcDexFile.getClasses().iterator();
                    return new Iterator<ClassDef>() {
                        @Override
                        public boolean hasNext() {
                            return sIter.hasNext();
                        }

                        @Override
                        public ClassDef next() {
                            ClassDef classDef = sIter.next();
                            if (replacerClasses.containsKey(classDef.getType())) {
                                classDef = replacerClasses.get(classDef.getType());
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
                    return srcDexFile.getClasses().size();
                }
            };
        }

        @Nonnull
        @Override
        public Opcodes getOpcodes() {
            return srcDexFile.getOpcodes();
        }
    }

    // Output a/b/c/XYZ.java
    // Ref: https://android.googlesource.com/toolchain/jack/+/ub-jack/jack/src/com/android/jack/ir/impl/JackIrBuilder.java
    @Nonnull
    public static String classToSourceName(@Nonnull ClassDef c) {
        if (c.getSourceFile() != null) {
            return rawTypeToPackagePath(c.getType()) + c.getSourceFile();
        }

        if (c.getType().contains("-$Lambda$")) {
            // Jack compiler may eliminate source info of lambda, guess it by method content.
            for (Method m : c.getDirectMethods()) {
                if (MethodUtil.isConstructor(m) || m.getImplementation() == null) continue;
                for (Instruction instr : m.getImplementation().getInstructions()) {
                    if (instr instanceof ReferenceInstruction) {
                        ReferenceInstruction refInstr = ((ReferenceInstruction) instr);
                        if (refInstr.getReference() instanceof MethodReference) {
                            MethodReference methodReference = null;
                            try {
                                methodReference = (MethodReference) refInstr.getReference();
                            } catch (DexBackedDexFile.InvalidItemIndex ex) {
                                LLog.ex(ex);
                            }
                            if (methodReference != null) {
                                String methodName = methodReference.getName();
                                if (methodName.startsWith("lambda$-")
                                        || methodName.lastIndexOf("-mthref-") > 0) {
                                    String owner = methodReference.getDefiningClass().substring(1);
                                    // Ignore inner class may have potential problem. Hope won't meet.
                                    int subPos = owner.indexOf("$");
                                    owner = subPos > 0 ? owner.substring(0, subPos)
                                            : owner.substring(0, owner.length() - 1);
                                    return owner + ".java";
                                }
                            }
                        }
                    }
                }
            }
        }

        return rawTypeToPackagePath(c.getType()) + c.getType() + ".unknown";
    }

    @Nonnull
    public static String rawTypeToPackagePath(@Nonnull String type) {
        int lastSlash = type.lastIndexOf('/') + 1;
        if (lastSlash > 0) {
            return type.substring(1, lastSlash);
        }
        return type.substring(1);
    }

    @Nonnull
    public static String simpleName(@Nonnull ClassDef c) {
        String type = c.getType();
        int lastSlash = type.lastIndexOf('/') + 1;
        if (lastSlash > 0) {
            return type.substring(lastSlash, type.length() - 1);
        }
        return type.substring(1, type.length() - 1);
    }

    public static void createDexJar(@Nonnull String[] files, @Nonnull String output) {
        Manifest manifest = new Manifest();
        Attributes attribute = manifest.getMainAttributes();
        attribute.putValue("Manifest-Version", "1.0");

        final byte[] buf = new byte[8192];
        int readSize;
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(output), manifest)) {
            String idx = "";
            int i = 1;
            for (String file : files) {
                try (FileInputStream in = new FileInputStream(file)) {
                    String filename = file.replace('\\', '/');
                    filename = filename.substring(filename.lastIndexOf('/') + 1);
                    if (filename.endsWith(".dex")) {
                        jos.putNextEntry(new ZipEntry(
                                i > 0 ? "classes.dex" : "classes" + idx + ".dex"));
                        idx = String.valueOf(++i);
                    } else {
                        jos.putNextEntry(new ZipEntry(filename));
                    }
                    while ((readSize = in.read(buf, 0, buf.length)) != -1) {
                        jos.write(buf, 0, readSize);
                    }
                }
                jos.closeEntry();
            }
        } catch (IOException e) {
            DLog.ex(e);
        }
    }

    @Nonnull
    public static String toReadableType(@Nonnull String type) {
        switch (type.charAt(0)) {
            case PRIM_VOID:
                return "void";
            case PRIM_BOOLEAN:
                return "boolean";
            case TYPE_OBJECT:
                return type.substring(1, type.length() - 1).replace("/", ".");
            case PRIM_CHAR:
                return "char";
            case PRIM_BYTE:
                return "byte";
            case PRIM_SHORT:
                return "short";
            case PRIM_INT:
                return "int";
            case PRIM_FLOAT:
                return "float";
            case PRIM_LONG:
                return "long";
            case PRIM_DOUBLE:
                return "double";
            case TYPE_ARRAY:
                return toReadableType(type.substring(1)) + "[]";
        }
        return "void";
    }

    public final static char PRIM_VOID = 'V';
    public final static char PRIM_BOOLEAN = 'Z';
    public final static char PRIM_BYTE = 'B';
    public final static char PRIM_CHAR = 'C';
    public final static char PRIM_SHORT = 'S';
    public final static char PRIM_INT = 'I';
    public final static char PRIM_FLOAT = 'F';
    public final static char PRIM_LONG = 'J';
    public final static char PRIM_DOUBLE = 'D';
    public final static char TYPE_ARRAY = '[';
    public final static char TYPE_OBJECT = 'L';
}
