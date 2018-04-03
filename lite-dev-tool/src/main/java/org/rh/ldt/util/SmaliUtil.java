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

import com.google.common.collect.Lists;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.TokenSource;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.builder.DexBuilder;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.smali.LexerErrorInterface;
import org.jf.smali.smaliFlexLexer;
import org.jf.smali.smaliParser;
import org.jf.smali.smaliTreeWalker;
import org.jf.util.IndentingWriter;
import org.rh.ldt.DLog;
import org.rh.smaliex.DexUtil;
import org.rh.smaliex.MiscUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SmaliUtil {

    // Output to xxx_dexjar
    public static void smali(File f) {
        long s = System.currentTimeMillis();
        String fn = f.getName();
        if (fn.endsWith("_smali")) {
            fn = fn.substring(0, fn.length() - 6);
        }
        String outputFolder = f.getParent() + "/" + fn + "_dexjar";
        String outDex = outputFolder + "/classes.dex";
        MiscUtil.mkdirs(new File(outputFolder));
        String cmd = "a " + f.getAbsolutePath() + " -o " + outDex;
        System.out.println("smali " + cmd.replace("\\", "/"));
        org.jf.smali.Main.main(cmd.split(" "));
        String outJar = outputFolder + "/" + fn + ".jar";
        // TODO multi-dex
        DexUtilEx.createDexJar(new String[]{outDex}, outJar);
        System.out.println("Output jar: " + outJar.replace("\\", "/"));
        System.out.println("smali complete, " + (System.currentTimeMillis() - s) + "ms\n");
    }

    // Output to xxx_smali
    public static void baksmali(File f) {
        long s = System.currentTimeMillis();
        String fn = f.getName();
        int dotPos = fn.lastIndexOf('.');
        if (dotPos > 0) {
            fn = fn.substring(0, dotPos);
        }
        String outputFolder = f.getParent() + "/" + fn + "_smali";
        String cmd = "d " + f.getAbsolutePath() + " -o " + outputFolder;
        System.out.println("baksmali " + cmd.replace("\\", "/"));
        try {
            org.jf.baksmali.Main.main(cmd.split(" "));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        System.out.println("Ouput folder: " + outputFolder.replace("\\", "/"));
        System.out.println("baksmali complete, " + (System.currentTimeMillis() - s) + "ms\n");
    }

    public static String getSmaliContent(ClassDef classDef) {
//        return getSmaliContent(classDef, (ClassPath) null);
        BaksmaliOptions options = new BaksmaliOptions();
        options.apiLevel = DexUtil.DEFAULT_API_LEVEL;
        options.accessorComments = false;
        ClassDefinition cd = new ClassDefinition(options, classDef);
        StringWriter sw = new StringWriter(1024);
        try {
            IndentingWriter writer = new IndentingWriter(sw);
            cd.writeTo(writer);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return sw.toString();
    }

    public static String getSmaliContent(ClassDef classDef, ClassPath classPath) {
        BaksmaliOptions options = new BaksmaliOptions();
        if (classPath != null) {
            options.apiLevel = classPath.isArt()
                    ? VersionMap.mapApiToArtVersion(classPath.oatVersion)
                    : DexUtil.DEFAULT_API_LEVEL;
            options.allowOdex = true;
            options.deodex = true;
            options.classPath = classPath;
        }
        options.accessorComments = false;
        return getSmaliContent(classDef, options);
    }

    public static String getSmaliContent(ClassDef classDef, BaksmaliOptions options) {
        ClassDefinition cd = new ClassDefinition(options, classDef);
        StringWriter sw = new StringWriter(4096);
        try {
            IndentingWriter writer = new IndentingWriter(sw);
            cd.writeTo(writer);
        } catch (IOException ex) {
            DLog.ex(ex);
        }
        return sw.toString();
    }

    @Nullable
    public static ClassDef assembleSmali(String smaliContent) {
        try {
            final DexBuilder dexBuilder = new DexBuilder(DexUtilEx.getDefaultOpCodes());
            LexerErrorInterface lexer = new smaliFlexLexer(new StringReader(smaliContent));
            CommonTokenStream tokens = new CommonTokenStream((TokenSource) lexer);
            smaliParser parser = new smaliParser(tokens);
            //parser.setApiLevel(DexUtil.API_LEVEL);
            smaliParser.smali_file_return result = parser.smali_file();

            if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0) {
                return null;
            }

            CommonTree t = result.getTree();
            CommonTreeNodeStream treeStream = new CommonTreeNodeStream(t);
            treeStream.setTokenStream(tokens);

            smaliTreeWalker dexGen = new smaliTreeWalker(treeStream);
            dexGen.setDexBuilder(dexBuilder);
            return dexGen.smali_file();
        } catch (RecognitionException ex) {
            DLog.ex(ex);
        }
        return null;
    }

    public static void baksmali(String inputDexFile, String outputDirectory) {
        BaksmaliOptions options = new BaksmaliOptions();

        File dexFile = new File(inputDexFile);
        File outputDir = new File(outputDirectory);
        List<DexBackedDexFile> dexFiles = DexUtilEx.loadMultiDex(dexFile);
        int index = 1;
        for (DexBackedDexFile dex : dexFiles) {
            org.jf.baksmali.Baksmali.disassembleDexFile(dex, outputDir, 4, options);
            outputDir = new File(outputDirectory + (++index));
        }
    }

    public static void assembleSmaliFile(String smaliFolder) {
        assembleSmaliFile(smaliFolder, DexUtil.DEFAULT_API_LEVEL,
                Runtime.getRuntime().availableProcessors());
    }

    public static void assembleSmaliFile(String smaliFolder, final int apiLevel, final int jobs) {
        ExecutorService executor = Executors.newFixedThreadPool(jobs);
        long s = System.currentTimeMillis();
        final DexBuilder dexBuilder = new DexBuilder(Opcodes.forApi(apiLevel));
        LinkedHashSet<File> filesToProcess = new LinkedHashSet<>();
        getSmaliFilesInDir(new File(smaliFolder), filesToProcess);

        List<Future<Boolean>> tasks = Lists.newArrayList();
        for (final File file : filesToProcess) {
            tasks.add(executor.submit(() -> assembleSmaliFile(file, dexBuilder, apiLevel)));
        }

        for (Future<Boolean> task : tasks) {
            while (true) {
                try {
                    if (!task.get()) {
                        DLog.i("error");
                    }
                } catch (InterruptedException iex) {
                    DLog.ex(iex);
                    continue;
                } catch (ExecutionException eex) {
                    DLog.ex(eex);
                }
                break;
            }
        }
        executor.shutdown();

        String outputDexFile = smaliFolder + ".dex";
        try {
            dexBuilder.writeTo(new FileDataStore(new File(outputDexFile)));
        } catch (IOException ex) {
            DLog.ex(ex);
        }
        DLog.i("cost " + (System.currentTimeMillis() - s) + " ms");
    }

    private static boolean assembleSmaliFile(File smaliFile, DexBuilder dexBuilder, int apiLevel)
            throws Exception {

        FileInputStream fis = new FileInputStream(smaliFile.getAbsolutePath());
        try (InputStreamReader reader = new InputStreamReader(fis, "UTF-8")) {
            LexerErrorInterface lexer = new smaliFlexLexer(reader);
            ((smaliFlexLexer) lexer).setSourceFile(smaliFile);
            CommonTokenStream tokens = new CommonTokenStream((TokenSource) lexer);
            smaliParser parser = new smaliParser(tokens);
            parser.setApiLevel(apiLevel);
            smaliParser.smali_file_return result = parser.smali_file();

            if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0) {
                return false;
            }

            CommonTree t = result.getTree();
            CommonTreeNodeStream treeStream = new CommonTreeNodeStream(t);
            treeStream.setTokenStream(tokens);

            smaliTreeWalker dexGen = new smaliTreeWalker(treeStream);
            dexGen.setDexBuilder(dexBuilder);
            dexGen.smali_file();
            return dexGen.getNumberOfSyntaxErrors() == 0;
        }
    }

    private static void getSmaliFilesInDir(@Nonnull File dir, @Nonnull Set<File> smaliFiles) {
        for (File file : FileUtil.listFiles(dir)) {
            if (file.isDirectory()) {
                getSmaliFilesInDir(file, smaliFiles);
            } else if (file.getName().endsWith(".smali")) {
                smaliFiles.add(file);
            }
        }
    }

    public static DexFile forceOdex2dex(final DexFile d, ClassPath classPath) {
        //classPath.oatVersion
        Opcodes.forArtVersion(classPath.oatVersion);
        BaksmaliOptions options = new BaksmaliOptions();
        options.classPath = classPath;
        options.apiLevel = d.getOpcodes().api;
        options.allowOdex = true;
        options.deodex = true;
        options.accessorComments = false;
        final HashSet<ClassDef> out = new HashSet<>();
        for (ClassDef c : d.getClasses()) {
            String s = SmaliUtil.getSmaliContent(c, options);
            out.add(SmaliUtil.assembleSmali(s));
        }
        return new DexFile() {
            @Override
            public Set<? extends ClassDef> getClasses() {
                return out;
            }

            @Nonnull
            @Override
            public Opcodes getOpcodes() {
                return d.getOpcodes();
            }
        };
    }
}
