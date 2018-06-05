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

import com.android.ddmlib.Device;

import org.rh.ldt.DLog;
import org.rh.ldt.DexReplacer;
import org.rh.ldt.Env;
import org.rh.ldt.Project;
import org.rh.ldt.util.AdbUtilEx;
import org.rh.ldt.util.DeviceUtil;
import org.rh.ldt.util.FileUtil;
import org.rh.ldt.util.StringUtil;
import org.rh.smaliex.AdbUtil;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpringLayout;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectPanel extends JPanel {

    public static JPanel create() {
        JPanel basePanel = new JPanel(new BorderLayout());
        basePanel.add(new PathPanel(), BorderLayout.NORTH);

        ProjectPanel pp = new ProjectPanel();
        pp.setLayout(new BoxLayout(pp, BoxLayout.Y_AXIS));
        JScrollPane sp = new JScrollPane(pp);
        sp.getVerticalScrollBar().setUnitIncrement(10);
        basePanel.add(sp, BorderLayout.CENTER);

        return basePanel;
    }

    private static class ProjectItem {
        final Project project;
        final DevicePanel panel;

        private ProjectItem(Project p) {
            project = p;
            panel = new DevicePanel(p);
            p.addStatusChangeListener(panel);
        }
    }

    private final PathMonitor mPathMonitor = new PathMonitor();
    private final ConcurrentHashMap<Device, ProjectItem> mProjectByDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProjectItem> mProjectByName = new ConcurrentHashMap<>();

    private ProjectPanel() {
        File[] cds = new File(Env.getWorkspace()).listFiles(File::isDirectory);
        if (cds != null && cds.length > 0) {
            Arrays.sort(cds, (File f1, File f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            for (File d : cds) {
                File projectProp = new File(d, Project.PROP_FILE_BUILD);
                if (projectProp.exists()) {
                    addProject(Env.loadProp(projectProp), null, false, false);
                }
            }
            SwingUtilities.invokeLater(this::updateUI);
        }

        if (!Env.NO_ADB) {
            AdbUtilEx.startAdb(new AdbUtilEx.DeviceListener() {

                @Override
                public void deviceConnected(final Device device) {
                    if (device == null) {
                        DLog.i("null deviceConnected");
                        return;
                    }
                    if (!AdbUtilEx.isOnline(device)) {
                        DLog.i(device + " is offline");
                        return;
                    }
                    device.logError = false;
                    int tries = 5;
                    while (tries-- > 0) {
                        if (AdbUtilEx.isFileExist(device, "/init.rc")) {
                            break;
                        }
                    }

                    String ret = AdbUtilEx.root(device);
                    if (ret != null && ret.contains("already")) {
                        AdbUtilEx.remount(device);
                    }
                    DLog.i(device.getName() + " connected");
                    addProject(device);
                }

                @Override
                public void deviceDisconnected(final Device device) {
                    if (device == null) {
                        DLog.i("null deviceDisconnected");
                        return;
                    }
                    removeProject(device, null);
                    DLog.i(device.getName() + " disconnected");
                }
            });
        }

        monitorWorkspace();
    }

    private void addProject(Properties prop, ProjectItem pi, boolean top, boolean refresh) {
        ProjectItem p = pi == null ? new ProjectItem(new Project(prop)) : pi;
        if (mProjectByName.get(p.project.name) == null) {
            mProjectByName.put(p.project.name, p);
            add(p.panel, top ? 0 : -1);
            if (p.project.isInitialized()) {
                monitorProject(p.project);
            }
        }
        if (refresh) {
            updateUI();
        }
    }

    private void addProject(Properties prop, ProjectItem pi, boolean top) {
        addProject(prop, pi, top, true);
    }

    private void addProject(Device device) {
        SwingUtilities.invokeLater(() -> {
            final String name = Project.device2Name(device);
            ProjectItem p = mProjectByName.get(name);
            if (p != null) { // Existed project connected
                p.project.setDevice(device);
                mProjectByDevice.put(device, p);
                remove(p.panel);
                add(p.panel, 0);
                updateUI();
             } else { // New connected
                MainUi.execTask(new MainUi.Task() {
                    ProjectItem pi;
                    @Override
                    public void run() {
                        Project p = new Project(device);
                        if (p.iValid()) {
                            device.logError = true;
                            pi = new ProjectItem(p);
                        }
                    }

                    @Override
                    public void done() {
                        if (pi != null) {
                            mProjectByDevice.put(device, pi);
                            addProject(null, pi, true);
                        }
                    }
                });
            }
        });
    }

    private void removeProject(final Device device, final String name) {
        SwingUtilities.invokeLater(() -> {
            boolean byDevice = device != null;
            ProjectItem p = byDevice ?
                    mProjectByDevice.get(device) : mProjectByName.get(name);
            if (p != null) {
                p.project.removeStatus(byDevice ?
                        Project.STATUS_ONLINE : Project.STATUS_INITIALIZED);
                if (p.project.isGone()) {
                    removeProjectFromPanel(p);
                }
            }
            updateUI();
        });
    }

    private void removeProjectFromPanel(ProjectItem p) {
        if (p.project.getDevice() != null) {
            mProjectByDevice.remove(p.project.getDevice());
        }
        mProjectByName.remove(p.project.name);
        remove(p.panel);
    }

    private void monitorProject(Project project) {
        PathMonitor.ChangeEvent ce = (File file, WatchEvent.Kind<?> e) -> {
            if (!Project.PROP_FILE_BUILD.equals(file.getName())) {
                return;
            }
            DLog.i(file + " deleted");
            if (e == StandardWatchEventKinds.ENTRY_DELETE) {
                removeProject(null, file.getParentFile().getName());
            } else {
                UiUtil.scheduleTask(() -> {
                    try {
                        if (project.propFile.exists()) {
                            DLog.i("Reload " + project.propFile);
                            project.load(Env.loadProp(project.propFile));
                        } else {
                            DLog.i("No longer exists " + project.propFile.getParent());
                            removeProject(null, project.folder.getName());
                        }
                    } catch (Exception ex) {
                        DLog.ex(ex);
                    }
                }, 1000);
            }
        };
        mPathMonitor.monitorPath(project.folder.getAbsolutePath(), ce,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
    }

    private void monitorWorkspace() {
        final String ws = Env.getWorkspace();
        FileUtil.mkdirs(new File(ws));
        PathMonitor.ChangeEvent ce = (File file, WatchEvent.Kind<?> e) -> {
            DLog.i("Project " + file.getName() + " add");
            UiUtil.scheduleTask(() -> {
                try {
                    File pf = new File(file, Project.PROP_FILE_BUILD);
                    if (pf.exists()) {
                        addProject(Env.loadProp(pf), null, true);
                    }
                } catch (Exception ex) {
                    DLog.ex(ex);
                }
            }, 500);
        };
        mPathMonitor.monitorPath(ws, ce, StandardWatchEventKinds.ENTRY_CREATE);
        new Thread(mPathMonitor, "WorkspaceMonitor").start();
    }

    static boolean configTargetSource(DexReplacer.ReplaceInfo info) {
        JTextArea t = new JTextArea(info.isAllEmpty()
                ? "# Empty, just show a sample\n" + DexReplacer.SAMPLE : info.toString());
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(400, 160));
        Object[] options = {"Apply", "Cancel"};
        int sel = JOptionPane.showOptionDialog(MainUi.self(),
                sp, "Config target sources",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, options, options[0]);
        // TODO add option to exec replacer
        if (sel == 0) {
            info.clear();
            info.read(new java.io.StringReader(t.getText()));
            return true;
        }
        return false;
    }

    static class DevicePanel extends JPanel implements Project.StatusChangeListener {
        final static int ITEM_HEIGHT = 120;
        final JLabel mProjectName = new JLabel();
        final Project mProject;
        final ButtonPanel mBtnPanel;

        DevicePanel(final Project project) {
            super(new BorderLayout());
            mProject = project;
            mBtnPanel = new ButtonPanel(project);
            project.addStatusChangeListener(mBtnPanel);
            SpringLayout layout = new SpringLayout();
            JPanel btnContainer = new JPanel(layout);

            AbstractButton app = new AppProjectButton();
            app.setVisible(false);
            btnContainer.add(mBtnPanel);
            btnContainer.add(app);
            layout.putConstraint(SpringLayout.EAST, btnContainer, 0, SpringLayout.EAST, mBtnPanel);
            mBtnPanel.setPreferredSize(new Dimension(180, ITEM_HEIGHT - 6));
            layout.putConstraint(SpringLayout.WEST, mBtnPanel, 2, SpringLayout.EAST, app);
            layout.putConstraint(SpringLayout.SOUTH, app, 0, SpringLayout.SOUTH, btnContainer);
            add(btnContainer, BorderLayout.EAST);

            final JCheckBox enableBuild = new JCheckBox("<html><p style='color:#3344bb'>"
                    + "[ Drop source file/folder to auto build ]</p></html>");
            enableBuild.setHorizontalTextPosition(SwingConstants.LEFT);
            enableBuild.setToolTipText("Unchecked will only copy source");
            enableBuild.setMaximumSize(new Dimension(260, 20));
            enableBuild.setSelected(true); // TODO save enableBuild to prop

            Droppable.Panel infoPanel = new Droppable.Panel() {
                @Override
                public void onDrop(final File[] files) {
                    DLog.i("ProjectPanel onDrop " + files.length + " files");
                    MainUi.execTask(() -> {
                        boolean remain = mProject.autoBuildFromDropFiles(
                                files, enableBuild.isSelected());
                        if (remain) {
                            for (File f : files) {
                                if (f.getName().endsWith(".apk")) {
                                    mProject.initAppProject(f);
                                }
                            }
                        }
                    });
                }
            };
            project.setSourceSelector(info -> {
                File rc = new File(project.folder, DexReplacer.DEFAULT_CONFIG);
                if (rc.exists()) {
                    return;
                }
                configTargetSource(info);
            });
            updateLabel();
            infoPanel.add(mProjectName);
            infoPanel.add(enableBuild);
            infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));

            setBorder(BorderFactory.createSoftBevelBorder(BevelBorder.RAISED));
            setPreferredSize(new Dimension(620, ITEM_HEIGHT));
            setMaximumSize(new Dimension(3000, ITEM_HEIGHT));
            add(infoPanel, BorderLayout.CENTER);
        }

        final void updateLabel() {
            mProjectName.setText("<html>" + mProject.name + " (" + mProject.buildInfo + ")"
                    + "<p style='color:#" + (mProject.isOnline() ? "227722" : "555588")
                    + "'>Status: " + mProject.getStatusString() + "</p></html>");
        }

        @Override
        public void onChange(int state) {
            updateLabel();
        }

        static class AppProjectButton extends JToggleButton {
            static Icon COLLAPSED = UIManager.getIcon("Tree.collapsedIcon");
            static Icon EXPANDED = javax.swing.UIManager.getIcon("Tree.expandedIcon");

            AppProjectButton() {
                super(COLLAPSED);
                setIconTextGap(getIconTextGap() - 10);
                setText("App projects");
            }

            @Override
            protected void fireActionPerformed(ActionEvent event) {
                super.fireActionPerformed(event);
                setIcon(isSelected() ? EXPANDED : COLLAPSED);
            }
        }

        private static class ButtonPanel extends JPanel implements Project.StatusChangeListener {
            int x, y;

            ButtonPanel(Project p) {
                super(new GridBagLayout());
                setBorder(BorderFactory.createEtchedBorder());
                setPreferredSize(new Dimension(200, HEIGHT));
                addButton(new BtnProject(p));
                addButton(new BtnBuild(p));
                addButton(new BtnPush(p));
                addButton(new BtnReboot(p));
                addButton(new BtnLogcat(p));
                addButton(new BtnDevice(p));
            }

            final void addButton(MenuButton button) {
                button.updateStatus(button.mProject.getStatus());
                button.addActionListener(button);
                GridBagConstraints gbc = new GridBagConstraints();
                gbc.gridx = x++ % 2;
                gbc.gridy = y = (x + 1) % 2 == 0 ? y + 1 : y;
                gbc.gridwidth = 1;
                gbc.gridheight = 1;
                gbc.weightx = 1;
                gbc.weighty = 1;
                gbc.fill = GridBagConstraints.BOTH;
                gbc.anchor = GridBagConstraints.CENTER;
                add(button, gbc);
            }

            @Override
            public void onChange(int state) {
                for (int i = 0; i < getComponentCount(); i++) {
                    java.awt.Component c = getComponent(i);
                    if (c instanceof MenuButton) {
                        MenuButton b = (MenuButton) c;
                        b.updateStatus(state);
                    }
                }
            }
        }

        private static class MenuItem extends JMenuItem  {
            final int requireStatus;
            MenuItem(String text, int rs) {
                super(text);
                requireStatus = rs;
            }
        }

        private static class MenuButton extends JButton implements ActionListener {
            final Project mProject;
            final int mRequireStatus;
            final boolean mAsync;
            final JPopupMenu mMenu = new JPopupMenu();

            MenuButton(String text, Project p, int rs, boolean async) {
                super(text);
                mProject = p;
                mRequireStatus = rs;
                mAsync = async;
            }

            MenuButton(String text, Project p, int rs) {
                this(text, p, rs, false);
            }

            void updateStatus(int s) {
                boolean enable = (mRequireStatus & s) != 0;
                setEnabled(enable);
                if (enable) {
                    for (int i = mMenu.getComponentCount() - 1; i >= 0; i--) {
                        Component c = mMenu.getComponent(i);
                        if (c instanceof MenuItem) {
                            MenuItem mi = (MenuItem) c;
                            if (mi.requireStatus != 0) {
                                mi.setEnabled((mi.requireStatus & s) != 0);
                            }
                        }
                    }
                }
            }

            final JMenuItem addMenuItem(String title, ActionListener action,
                    int rs, boolean async) {
                MenuItem menuItem = new MenuItem(title, rs);
                menuItem.addActionListener(async ?
                        e -> MainUi.execTask(() -> action.actionPerformed(e)) : action);
                mMenu.add(menuItem);
                return menuItem;
            }

            final JMenuItem addMenuItem(String title, ActionListener action) {
                return addMenuItem(title, action, 0, mAsync);
            }

            @Override
            public void actionPerformed(final ActionEvent e) {
                mMenu.show((Component) e.getSource(), 3, 28);
            }
        }

        static class BtnProject extends MenuButton {
            BtnProject(Project p) {
                super("Project", p, Project.STATUS_ONLINE | Project.STATUS_INITIALIZED);
                addMenuItem("init", event -> {
                    if (mProject.folder.isDirectory()) {
                        MainUi.execTaskWithConfirm(() -> mProject.initProject(true),
                                "Re-init", "Force re-init " + mProject.name);
                    } else {
                        MainUi.execTask(mProject::initProject);
                    }
                });

                addMenuItem("open source folder", event -> {
                    String path = FileUtil.path(
                            mProject.folder.getAbsolutePath(), Project.DIR_SRC);
                    Env.openPcFileBrowser(path, false);
                }, Project.STATUS_INITIALIZED, false);

                addMenuItem("config output target", event -> {
                    if (!mProject.replaceConfig.exists()) {
                        MainUi.showToast(mProject.replaceConfig + " not created");
                        return;
                    }
                    DexReplacer.ReplaceInfo info = new DexReplacer.ReplaceInfo(
                            mProject.replaceConfig);
                    if (configTargetSource(info)) {
                        info.saveTo(mProject.replaceConfig);
                    }
                });

                addMenuItem("search class", event -> MainUi.showToast("not impl yet"));
                        // TODO event -> new SearchFrame().setVisible(true));

                addMenuItem("delete", event -> MainUi.execTaskWithConfirm(
                        mProject::delete, "Delete", "Delete " + mProject.name));
            }
        }

        static class BtnBuild extends MenuButton {

            BtnBuild(Project p) {
                super("Build", p, Project.STATUS_INITIALIZED, true);
                addMenuItem("all jars", event -> {
                    if (mProject.buildSrcToDex()) {
                        mProject.makeJar();
                    }
                });
                addMenuItem("dex", event -> mProject.buildSrcToDex());
                addMenuItem("classes (aidl+java)", event -> mProject.buildSrcWithAidl());
                addMenuItem("java only", event -> mProject.build("compile"));
                addMenuItem("aidl only", event -> mProject.build("aidl"));
                addMenuItem("jar only", event -> mProject.makeJar());
                addMenuItem("clean build", event -> {
                    mProject.build("clean");
                    mProject.buildSourceAndMakeJar();
                });
            }
        }

        static class BtnPush extends MenuButton {
            BtnPush(Project p) {
                super("Push", p, Project.STATUS_ONLINE);
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                File outDir = new File(mProject.folder, DexReplacer.OUTPUT_FOLDER);
                File[] jars = outDir.listFiles();
                if (jars != null) {
                    mMenu.removeAll();
                    for (final File j : jars) {
                        if (j.getName().contains(DexReplacer.PREVIOUS_JAR_POSTFIX)) {
                            continue;
                        }
                        String name = j.getName() + " (" + new java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss").format(
                                new java.util.Date(j.lastModified())) + ")";
                        addMenuItem(name, event -> MainUi.execTask(() -> {
                            AdbUtilEx.pushFile(mProject.getDevice(), j.getAbsolutePath(),
                                    Project.DEVICE_FRAMEWORK_PATH + j.getName());
                            remindUpdateFramework(j.getName());
                        }));
                    }
                    super.actionPerformed(e);
                } else {
                    MainUi.showToast("No out jars");
                }
            }

            void remindUpdateFramework(String targetJar) {
                // TODO provide ui to rename /system/framework/<abi>*
                String[] bcp = mProject.getBootClassPath();
                if (bcp != null) {
                    final String rj = StringUtil.getOnlyFilename(targetJar);
                    boolean inBoot = false;
                    for (String bootJarOfDevice : bcp) {
                        if (StringUtil.getOnlyFilename(bootJarOfDevice, '/').equals(rj)) {
                            inBoot = true;
                            break;
                        }
                    }
                    if (inBoot) {
                        String[] abiList = {"arm", "arm64", "x86", "x64"};
                        for (String abi : abiList) {
                            String checkPath = "/system/framework/" + abi;
                            if (AdbUtil.isFileExist(mProject.getDevice(), checkPath)) {
                                DLog.i("The replacing jar is in boot classpath, it may "
                                        + "not work if oat/odex file exists in " + checkPath);
                                break;
                            }
                        }
                    }
                }
            }
        }

        static class BtnReboot extends MenuButton {
            BtnReboot(Project p) {
                super("Reboot", p, Project.STATUS_ONLINE, true);
                addMenuItem("Quick",
                        event -> AdbUtilEx.shell(mProject.getDevice(), "stop;start"));
                addMenuItem("Full",
                        event -> AdbUtilEx.reboot(mProject.getDevice(), ""));
                addMenuItem("Bootloader",
                        event -> AdbUtilEx.rebootToBootloader(mProject.getDevice()));
                addMenuItem("downloadmode",
                        event -> AdbUtilEx.reboot(mProject.getDevice(), "download"));
                addMenuItem("disable-verity",
                        event -> {
                            String ret = AdbUtilEx.shell(mProject.getDevice(), "disable-verity");
                            if (ret.contains("reboot")) {
                                AdbUtilEx.reboot(mProject.getDevice(), "");
                            } else {
                                MainUi.showToast(ret);
                            }
                        });
            }
        }

        static class BtnLogcat extends MenuButton {
            BtnLogcat(Project p) {
                super("Logcat", p, Project.STATUS_ONLINE);
                for (LogAttr.LogSource logSrc : LogAttr.LogSource.values()) {
                    addMenuItem(logSrc.name, event -> new LogFrame(mProject.name
                            + " (log source:" + logSrc.name + ")")
                            .start(mProject.getDevice(), logSrc));
                }
            }
        }

        static class BtnDevice extends MenuButton {

            // TODO setprop dalvik.vm.dex2oat-filter
            // verify-none, interpret-only, verify-at-runtime
            // space, balanced, speed, everything, time
            // dalvik.vm.usejit=true or debug.usejit=true
            // dalvik.vm.dex2oat-thread_count
            // dalvik.vm.dex2oat-threads
            // dalvik.vm.image-dex2oat-threads
            BtnDevice(Project p) {
                super("Device", p, Project.STATUS_ONLINE);
                addMenuItem("Refresh adb", event -> AdbUtilEx.startAdb(null));
                addMenuItem("Screen control", event -> {
                    ScreenController c = new ScreenController(mProject.getDevice());
                    c.show();
                });

                addMenuItem("File explorer", event -> {}).setEnabled(false);
                addMenuItem("Get boot image",
                        event -> DeviceUtil.getBootImage(mProject.getDevice(),
                                mProject.folder.getAbsolutePath()),
                        Project.STATUS_ONLINE, true);
            }
        }
    }

    static class PathPanel extends JPanel {

        PathPanel() {
            super(new GridBagLayout());
            add(Box.createHorizontalStrut(10));
            addPathSubPanel("Workspace", Env.PROP_WORKSPACE, Env.getWorkspace());
            add(Box.createHorizontalStrut(10));
            addPathSubPanel("Tool", Env.PROP_TOOL_PATH, Env.TOOL_PATH);
            add(Box.createHorizontalStrut(20));
        }

        final void addPathSubPanel(String label, String key, final String value) {
            JLabel wsLabel = new JLabel(label);
            wsLabel.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
            add(wsLabel, newGbc(2));
            final JTextField pathField = new JTextField(80);
            pathField.addCaretListener(new CaretListener() {
                String mPreviousText = value;

                @Override
                public void caretUpdate(CaretEvent e) {
                    JTextField tf = (JTextField) e.getSource();
                    String text = tf.getText();
                    if (!text.equals(mPreviousText)) {
                        MainUi.showToast("Path change will need to restart");
                    }
                    mPreviousText = text;
                }
            });
            Env.addAutoSave(key, pathField);
            pathField.setText(value);
            add(pathField, newGbc(50));
            JButton open = new JButton(UIManager.getIcon("Tree.openIcon"));
            open.setToolTipText("Open the path");
            add(open, newGbc(1));
            open.addActionListener(e -> {
                String path = pathField.getText();
                if (path != null) {
                    File f = new File(path);
                    if (!f.isDirectory()) {
                        DLog.i("Not directory: " + path);
                        return;
                    }
                    Env.openPcFileBrowser(path, false);
                }
            });
        }

        static GridBagConstraints newGbc(int weightX) {
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = GridBagConstraints.RELATIVE;
            gbc.gridy = 0;
            gbc.gridwidth = 1;
            gbc.gridheight = 1;
            gbc.weightx = weightX;
            gbc.weighty = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            return gbc;
        }
    }
}
