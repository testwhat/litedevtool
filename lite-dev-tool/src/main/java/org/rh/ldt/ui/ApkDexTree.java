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

package org.rh.ldt.ui;

import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.rh.ldt.DLog;
import org.rh.ldt.util.DexUtilEx;
import org.rh.ldt.util.FileUtil;
import org.rh.ldt.util.SmaliUtil;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class ApkDexTree extends Droppable.TreeView {
    private final ArrayList<NodeWrapper> mSelections = new ArrayList<>();
    private OpenAction mOpenAction;

    static JComponent create(OpenAction action) {
        ApkDexTree sct = new ApkDexTree();
        sct.mOpenAction = action;
        return new javax.swing.JScrollPane(sct);
    }

    static class OpenData {
        final public NodeDex dex;
        final public NodeClass cls;
        OpenData(NodeDex dex, NodeClass cls) {
            this.dex = dex;
            this.cls = cls;
        }
    }

    interface OpenAction {
        void onOpenSmali(final OpenData data);
    }

    ApkDexTree() {
        setRootVisible(false);
        setScrollsOnExpand(false);

        DefaultMutableTreeNode emptyRoot = new DefaultMutableTreeNode();
        emptyRoot.add(new DefaultMutableTreeNode("Drop dex / jar / apk(s) to view/edit"));
        final DefaultTreeModel model = new DefaultTreeModel(emptyRoot);
        setModel(model);
        final JPopupMenu popMenu = new JPopupMenu();
        final JMenuItem save = new JMenuItem("Save as");
        save.addActionListener(e -> {
            for (NodeWrapper node : mSelections) {
                if (node instanceof NodeDex) {
                    final NodeDex dex = (NodeDex) node;
                    MainUi.saveToFile(path -> {
                        DexFile df = dex.modifiedDex != null ? dex.modifiedDex : dex.dexFile;
                        DexUtilEx.writeDexFile(path.getAbsolutePath(), df);
                    }, "classes.dex", false);
                } else if (node instanceof NodeClass) {
                    final NodeClass cls = (NodeClass) node;
                    MainUi.saveToFile(path -> {
                        cls.smaliContent = SmaliUtil.getSmaliContent(cls.classDef);
                        FileUtil.writeTextFile(path.getAbsolutePath(), cls.smaliContent);
                    }, cls.simpleName + ".smali", false);
                } else if (node instanceof NodePackage) {
                    final NodePackage pkg = (NodePackage) node;
                    MainUi.saveToFile(path -> {
                        FileUtil.mkdirs(path);
                        for (int i = 0; i < pkg.getChildCount(); i++) {
                            NodeClass cls = (NodeClass) pkg.getChildAt(i);
                            cls.smaliContent = SmaliUtil.getSmaliContent(cls.classDef);
                            FileUtil.writeTextFile(FileUtil.path(
                                    path.getAbsolutePath(), cls.simpleName + ".smali")
                                    , cls.smaliContent);
                        }
                    }, pkg.name, true);
                }
            }
            mSelections.clear();
        });
        popMenu.add(save);

        addMouseListener(new java.awt.event.MouseAdapter() {

            NodeWrapper getNode(TreePath tp) {
                if (tp != null && tp.getLastPathComponent() instanceof NodeWrapper) {
                    return (NodeWrapper) tp.getLastPathComponent();
                }
                return null;
            }

            NodeDex getDex(TreePath tp) {
                return (NodeDex) tp.getPathComponent(1);
            }

            NodeClass getClass(TreePath tp) {
                NodeWrapper node = getNode(tp);
                if (node != null && node instanceof NodeClass) {
                    return (NodeClass) node;
                }
                return null;
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (mOpenAction != null
                        && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2
                        && getRowForLocation(e.getX(), e.getY()) != -1) {
                    TreePath selPath = getPathForLocation(e.getX(), e.getY());
                    NodeClass node = getClass(selPath);
                    if (node != null) {
                        mOpenAction.onOpenSmali(new OpenData(getDex(selPath), node));
                    }
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                //if (e.getClickCount() < 2) { // Force single selection
                //    setSelectionRow(getClosestRowForLocation(e.getX(), e.getY()));
                //}
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath[] tps = getSelectionPaths();
                    if (tps != null && tps.length > 0) {
                        mSelections.clear();
                        for (TreePath tp : tps) {
                            NodeWrapper node = getNode(tp);
                            if (node != null) {
                                mSelections.add(node);
                            }
                        }
                    }
                    if (!mSelections.isEmpty()) {
                        popMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });
    }

    @Override
    public void onDrop(final File[] files) {
        final ArrayList<DefaultMutableTreeNode> clsList = new ArrayList<>();
        MainUi.execTask(new MainUi.Task() {
            @Override
            public void run() {
                for (File f : files) {
                    if (f.isDirectory()) {
                        continue;
                    }
                    List<DexBackedDexFile> dfs = DexUtilEx.loadMultiDex(f);
                    for (int i = 0; i < dfs.size(); i++) {
                        DexBackedDexFile df = dfs.get(i);
                        clsList.add(getClassesTree(f, df, i));
                    }
                }
            }

            @Override
            public void done() {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode();
                clsList.forEach(root::add);
                setModel(new DefaultTreeModel(root));
            }
        });
    }

    static DefaultMutableTreeNode getClassesTree(File f, DexBackedDexFile df, int i) {
        TreeMap<String, ArrayList<NodeClass>> allClasses = new TreeMap<>();
        ArrayList<NodeClass> noPkgClasses = null;
        for (DexBackedClassDef c : df.getClasses()) {
            String cls = c.getType();
            cls = cls.substring(1, cls.length() - 1);
            int cp = cls.lastIndexOf('/');
            if (cp > 0) {
                String pkg = cls.substring(0, cp).replace('/', '.');
                ArrayList<NodeClass> classes = allClasses.computeIfAbsent(pkg, k -> new ArrayList<>());
                classes.add(new NodeClass(c, f, cls.substring(cp + 1)));
            } else {
                if (noPkgClasses == null) {
                    noPkgClasses = new ArrayList<>();
                }
                noPkgClasses.add(new NodeClass(c, f, cls));
            }
        }
        allClasses.values().forEach(java.util.Collections::sort);
        String rootName = f.getName();
        if (!rootName.endsWith(".dex")) {
            rootName += "/classes" + (i > 0 ? (i + 1) : "") + ".dex";
        }
        DefaultMutableTreeNode root = new NodeDex(rootName, df);
        //List<String> resItems = listAndroidResFromZip(f);
        //for (String rf : resItems) {
        //    root.add(new new ResourceItem(rf));
        //}
        DefaultMutableTreeNode parent;
        for (String pkg : allClasses.keySet()) {
            root.add(parent = new NodePackage(pkg));
            for (NodeClass c : allClasses.get(pkg)) {
                parent.add(c);
            }
        }
        if (noPkgClasses != null) {
            noPkgClasses.forEach(root::add);
        }
        return root;
    }

    public static List<String> listAndroidResFromZip(File zipFile) { // TODO
        ArrayList<String> res = new ArrayList<>();
        String fileName = zipFile.getName().toLowerCase();
        if (fileName.endsWith(".apk") || fileName.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(zipFile)) {
                java.util.Enumeration<? extends ZipEntry> zs = zip.entries();
                while (zs.hasMoreElements()) {
                    ZipEntry entry = zs.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("classes") && name.endsWith(".dex")) {
                        continue;
                    }
                    if (name.startsWith("res")) {
                        System.out.println(name);
                    }
                }
            } catch (Exception ex) {
                DLog.ex(ex);
            }
        }
        return res;
    }
}

class NodeWrapper extends DefaultMutableTreeNode {
}

class NodeDex extends NodeWrapper {
    public DexFile modifiedDex;
    public DexBackedDexFile dexFile;
    public final String filename;

    public NodeDex(String fn, DexBackedDexFile df) {
        filename = fn;
        dexFile = df;
    }

    @Override
    public String toString() {
        return filename;
    }
}

class NodePackage extends NodeWrapper {
    public final String name;

    public NodePackage(String pkgName) {
        name = pkgName;
    }

    @Override
    public String toString() {
        return name;
    }
}

class NodeClass extends NodeWrapper implements Comparable<NodeClass> {
    public final DexBackedClassDef classDef;
    public final String simpleName;
    public final File file;
    public String smaliContent;

    NodeClass(DexBackedClassDef c, File f, String n) {
        classDef = c;
        file = f;
        simpleName = n;
    }

    @Override
    public String toString() {
        return simpleName;
    }

    public String toTitle() {
        return file.getName() + "-" + simpleName;
    }

    @Override
    public int compareTo(NodeClass o) {
        return simpleName.compareTo(o.simpleName);
    }
}

// TODO
class ResourceItem extends NodeWrapper {

    public final String name;

    public ResourceItem(String n) {
        name = n;
    }

    @Override
    public String toString() {
        return name;
    }
}

class SearchFrame extends javax.swing.JFrame {
    SearchFrame() {
        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        JFilterTextField jtf = new JFilterTextField(3, 2);
        add(jtf, BorderLayout.PAGE_START);
        pack();
        setLocationRelativeTo(null);
    }
}
