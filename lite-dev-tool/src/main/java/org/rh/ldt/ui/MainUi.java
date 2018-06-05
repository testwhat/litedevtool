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

import org.rh.ldt.DLog;
import org.rh.ldt.Env;
import org.rh.ldt.util.AdbUtilEx;
import org.rh.ldt.util.SmaliUtil;

import javax.annotation.Nonnull;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.Painter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.event.HyperlinkEvent;
import javax.swing.filechooser.FileFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

public class MainUi extends JFrame {

    private static MainUi self;

    public static void main(String[] args) {
        initUIStyle();
        javax.swing.SwingUtilities.invokeLater(() -> {
            self = new MainUi();
            self.setVisible(true);
        });
    }

    private static final String APP_NAME = "Lite Dev Tool";
    private static final String VER = "0.1";
    private static JFileChooser sFileChooser;

    private final AtomicInteger mWorking = new AtomicInteger(0);
    private final JProgressBar mProgressBar;

    private final JTextArea mMsgArea;
    private SmaliViewer mSmaliViewer;

    private MainUi() {
        setTitle(APP_NAME + " " + VER);
        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setLocationByPlatform(true);
        redirectSystemStreams();

        mMsgArea = new ContextMenuUtil.ACMJTextArea();
        mMsgArea.setEditable(false);
        final JTabbedPane tabPane = new JTabbedPane();

        int width = Env.getInt(Env.PROP_WIN_W, 640);
        int height = Env.getInt(Env.PROP_WIN_H, 480);
        setSize(width, height);

        mProgressBar = new JProgressBar(JProgressBar.HORIZONTAL);
        mProgressBar.setVisible(false);
        mProgressBar.setIndeterminate(true);
        JPanel placer = new JPanel(new BorderLayout());
        placer.setMaximumSize(new Dimension(width * 10, 0));
        placer.setPreferredSize(new Dimension(0, 10));
        placer.add(mProgressBar, BorderLayout.CENTER);
        Box msgBox = Box.createVerticalBox();
        msgBox.setPreferredSize(new Dimension(width, 120));
        msgBox.add(placer);
        JScrollPane jsp = new JScrollPane(mMsgArea);
        jsp.setPreferredSize(new Dimension(0, 90));
        msgBox.add(jsp);

        JSplitPane base = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabPane, msgBox);
        base.setResizeWeight(0.6);
        base.setDividerSize(8);
        base.setOneTouchExpandable(true);
        getContentPane().add(base);

        tabPane.addTab("Project", ProjectPanel.create());
        ApkDexTree.OpenAction mOpenAction = data -> {
            if (mSmaliViewer == null) {
                mSmaliViewer = new SmaliViewer();
                mSmaliViewer.setBounds(400, 100, 800, 800);
            }
            runTask(new Task() {
                @Override
                public void run() {
                    data.cls.smaliContent = SmaliUtil.getSmaliContent(data.cls.classDef);
                }

                @Override
                public void done() {
                    mSmaliViewer.open(data.cls, data.dex);
                }
            });
        };
        tabPane.addTab("Dex View", ApkDexTree.create(mOpenAction));
        tabPane.addTab("Smali Asm", SmaliAsmPanel.create());
        tabPane.addTab("Method Compare", MethodComparePanel.create());
        //tabPane.addTab("Misc Utility", MiscPanel.create());

        JEditorPane aboutP = new JEditorPane();
        aboutP.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        aboutP.setContentType("text/html");
        aboutP.setText("<html>" + APP_NAME + " " + VER + " by R.H.<br/>"
                + "With <a href='https://bitbucket.org/JesusFreke/smali'>dexlib2,smali</a> "
                + org.jf.smali.Main.VERSION.replace("-dirty", "")
                + " <a href='http://www.apache.org/licenses/LICENSE-2.0'>Apache License"
                + ", Version 2.0</a><br/><br/> System: " + System.getProperty("os.name")
                + "<br/>Runtime: " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.runtime.version") + "<br/>"
                + "<br/>Java home: " + Env.JAVA_HOME + "<br/>"
                + "<br/>Classpath: " + Env.CLASSPATH.replace(
                Env.JAVA_HOME, "").replace(File.pathSeparator, File.pathSeparator + " ")
                + "<br/></html>");
        aboutP.setEditable(false);
        aboutP.setFocusable(false);
        aboutP.setOpaque(false);
        aboutP.setBackground(new java.awt.Color(0, 0, 0, 0));
        aboutP.addHyperlinkListener(event -> {
            if (HyperlinkEvent.EventType.ACTIVATED.equals(event.getEventType())) {
                Desktop desktop = Desktop.getDesktop();
                try {
                    desktop.browse(event.getURL().toURI());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
        tabPane.addTab("About", aboutP);
        int sel = Env.getInt(Env.PROP_TAB_INDEX, 0);
        if (sel < 0 || sel >= tabPane.getTabCount()) {
            sel = 0;
        }
        tabPane.setSelectedIndex(sel);

        ContextMenuUtil.enable();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Env.set(Env.PROP_TAB_INDEX, tabPane.getSelectedIndex());
                Env.set(Env.PROP_WIN_W, getWidth());
                Env.set(Env.PROP_WIN_H, getHeight());
                Env.save();
                AdbUtilEx.stopAdb();
            }
        });
    }

    static MainUi self() {
        return self;
    }

    public interface Task extends Runnable {
        default void prepare() {
        }

        default void done() {
        }
    }

    public static void execTask(@Nonnull final Task t) {
        self.runTask(t);
    }

    public static void execTaskWithConfirm(@Nonnull final Task t, String title, String msg) {
        if (UiUtil.confirm(title, msg)) {
            execTask(t);
        }
    }

    void runTask(@Nonnull final Task t) {
        mProgressBar.setVisible(true);
        t.prepare();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                mWorking.incrementAndGet();
                try {
                    t.run();
                } catch (Exception e) {
                    DLog.ex(e);
                }
                return null;
            }

            @Override
            protected void done() {
                if (mWorking.decrementAndGet() == 0) {
                    mProgressBar.setVisible(false);
                }
                t.done();
            }
        }.execute();
    }

    public static void showToast(String text) {
        UiUtil.showToast(self, text);
    }

    private void appendMsg(final String str) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendMsg(str));
            return;
        }
        mMsgArea.append(str);
        mMsgArea.setCaretPosition(mMsgArea.getDocument().getLength());
    }

    public interface SaveFile {
        void saveTo(File path);
    }

    public static void saveToFile(SaveFile save, String defaultName, final boolean dir) {
        if (sFileChooser == null) {
            sFileChooser = new JFileChooser(".//");
            sFileChooser.setFileFilter(new FileFilter() {
                @Override
                public boolean accept(File f) {
                    return dir == f.isDirectory();
                }

                @Override
                public String getDescription() {
                    return "*.*";
                }
            });
        }
        sFileChooser.setSelectedFile(new File(defaultName));
        int userSelection = sFileChooser.showSaveDialog(null);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = sFileChooser.getSelectedFile();
            DLog.i("Save to file: " + fileToSave.getAbsolutePath());
            save.saveTo(fileToSave);
        }
    }

    private void redirectSystemStreams() {
        FileOutputStream fdOut = new FileOutputStream(FileDescriptor.out);
        final PrintStream ps = new PrintStream(new BufferedOutputStream(fdOut, 128), true);
        OutputStream out = new OutputStream() {
            @Override
            public void write(final int b) {
                appendMsg(String.valueOf((char) b));
                ps.append((char) b);
            }

            @Override
            public void write(@Nonnull byte[] b, int off, int len) {
                String str = new String(b, off, len);
                appendMsg(str);
                ps.append(str);
            }

            @Override
            public void write(@Nonnull byte[] b) {
                write(b, 0, b.length);
                //ps.write(b, 0, b.length);
            }
        };
        System.setOut(new PrintStream(out, true));
        //System.setErr(new PrintStream(out, true));
    }

    private static void initUIStyle() {
        try {
            System.setProperty("awt.useSystemAAFontSettings","on");
            System.setProperty("swing.aatext", "true");
            //UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
            UIDefaults defaults = UIManager.getLookAndFeelDefaults();
            // http://stackoverflow.com/questions/6922368/override-swing-nimbus-lf-primary-color-per-component-instance
            defaults.put("TabbedPane:TabbedPaneTab[Enabled+Pressed].backgroundPainter", null);
            defaults.put("TabbedPane:TabbedPaneTab[Pressed+Selected].backgroundPainter", null);
            defaults.put("TabbedPane:TabbedPaneTab[Focused+Pressed+Selected].backgroundPainter", null);
            defaults.put("TabbedPane:TabbedPaneTabArea[Enabled+Pressed].backgroundPainter", null);

            if (System.getProperty("java.runtime.version").startsWith("1.8.0_6")) {
                // https://bugs.openjdk.java.net/browse/JDK-8041642
                defaults.put("ScrollBar.minimumThumbSize", new Dimension(30, 30));
                defaults.put("ProgressBar[Enabled+Indeterminate].foregroundPainter",
                        new ProgressPainter(new Color(125, 255, 125), new Color(25, 175, 25)));
            }
        } catch (ClassNotFoundException | InstantiationException
                | IllegalAccessException | UnsupportedLookAndFeelException e) {
            DLog.ex(e);
        }
    }
}

class ProgressPainter implements Painter {

    private final Color light, dark;

    ProgressPainter(Color light, Color dark) {
        this.light = light;
        this.dark = dark;
    }

    @Override
    public void paint(Graphics2D g, Object c, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        GradientPaint gradPaint = new GradientPaint(
                (w / 2.0f), 0, light, (w / 2.0f), (h / 2.0f), dark, true);
        g.setPaint(gradPaint);
        final int s = 4;
        g.fillRect(2, 2, (w - s), (h - s));

        Color outline = new Color(0, 85, 0);
        g.setColor(outline);
        g.drawRect(2, 2, (w - s), (h - s));
        Color trans = new Color(outline.getRed(), outline.getGreen(), outline.getBlue(), 100);
        g.setColor(trans);
        g.drawRect(1, 1, (w - 3), (h - 3));
    }
}