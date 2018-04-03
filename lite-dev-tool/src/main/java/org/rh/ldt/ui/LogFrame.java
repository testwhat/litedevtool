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

import com.android.ddmlib.CommandReceiverTask;
import com.android.ddmlib.Device;
import com.android.ddmlib.Log;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.HeadlessException;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

public class LogFrame extends JFrame {

    private final LoggerPane mLogPane = new LoggerPane();
    private JFilterTextField mIncludeTF;
    private JFilterTextField mExcludeTF;
    private JFilterTextField mHighlightTF;
    private UpdatePaneTaskUtil mTextUpdater;
    private int mFilterLen = 2;
    private JCheckBox mAutoScrollCB;
    private JRadioButton[] mLogLevelRadioBtns;
    private Timer mControlTimer = UiUtil.getTaskTimer();
    private Highlighter.HighlightPainter mHlPainter =
            new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW);
    private boolean mPause;
    private boolean mRestart;
    private LogAttr.LogSource mLogSource = LogAttr.LogSource.defaults;
    private Log.LogLevel mLevel = Log.LogLevel.VERBOSE;
    private CommandReceiverTask mLogTask;

    public LogFrame(String title) throws HeadlessException {
        setTitle(title);
        setLocationByPlatform(true);
        JPanel baseP = new JPanel(new BorderLayout());
        Box controlPanel = new Box(BoxLayout.Y_AXIS);
        controlPanel.add(newControlFirstLine());
        controlPanel.add(newControlSecondLine());
        baseP.add(controlPanel, BorderLayout.NORTH);
        baseP.add(new JScrollPane(mLogPane), BorderLayout.CENTER);
        setContentPane(baseP);
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (mLogTask != null) {
                    mLogTask.stop();
                }
            }
        });
    }

    void startLogTask() {
        new Thread() {
            @Override
            public void run() {
                mLogTask.run();
                if (mRestart) {
                    mRestart = false;
                    mLogTask.setCancelled(false);
                    startLogTask();
                }
            }
        }.start();
    }

    public void start(Device device, LogAttr.LogSource logSrc) {
        CommandReceiverTask.Logger t = new CommandReceiverTask.Logger() {
            @Override
            public void onLog(String[] logs) {
                for (String log : logs) {
                    if (mPause) {
                        continue;
                    }
                    if (log != null && log.length() > 1 && okToShow(log)) {
                        mTextUpdater.appendLogLine(log + "\n");
                    }
                }
            }

            @Override
            public void onStop(String reason) {
            }
        };
        mLogSource = logSrc;
        mLogTask = new CommandReceiverTask(device, logSrc.getCommand(), t);
        mTextUpdater = new UpdatePaneTaskUtil(this);
        startLogTask();
        setVisible(true);
    }

    public void restart() {
        mLogPane.setText("");
        mRestart = true;
        mLogTask.stop();
    }

    private static Log.LogLevel getLogLevel(char c) {
        Log.LogLevel lv = Log.LogLevel.getByLetter(c);
        return lv != null ? lv : Log.LogLevel.ERROR;
    }

    private boolean okToShow(String log) {
        if (mLogSource.hasLevel) {
            if (log.length() > 31
                    && getLogLevel(log.charAt(31)).getPriority() < mLevel.getPriority()) {
                return false;
            }
        }
        String ex = mExcludeTF.getCurrentLowerCaseStr();
        String in = mIncludeTF.getCurrentLowerCaseStr();
        boolean normalExclude = ex.length() > mFilterLen && !mExcludeTF.isRegExpMode();
        boolean normalInclude = in.length() > mFilterLen && !mIncludeTF.isRegExpMode();
        String msg = (normalExclude || normalInclude) ? log.toLowerCase() : "";
        if (normalExclude) {
            if (msg.contains(ex)) {
                return false;
            }
        } else if (mExcludeTF.isRegExpMode()) {
            if (mExcludeTF.match(log)) {
                return false;
            }
        }
        if (normalInclude) {
            return msg.contains(in);
        } else if (mIncludeTF.isRegExpMode()) {
            return mIncludeTF.match(log);
        }
        return true;
    }

    void appendLine(String log) {
        int start = -1, end = -1;
        if (mHighlightTF.getLength() > mFilterLen) {
            String hl = mHighlightTF.getCurrentLowerCaseStr();
            int pos = log.toLowerCase().indexOf(hl);
            if (pos > -1) {
                start = mLogPane.getDocument().getLength() + pos;
                end = start + hl.length();
            }
        }
        mLogPane.append(log);
        if (start > 0) {
            try {
                mLogPane.getHighlighter().addHighlight(start, end, mHlPainter);
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        }
    }

    static class LoggerPane extends ContextMenuUtil.ACMJTextArea {

        private boolean mIsAutoScroll = true;

        public LoggerPane() {
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            setLineWrap(true);
        }

        @Override
        public void append(String str) {
            super.append(str);
            if (mIsAutoScroll) {
                int len = getDocument().getLength();
                int selEnd = getSelectionEnd();
                if (selEnd != len && selEnd == getSelectionStart()) {
                    setCaretPosition(len);
                }
            }
        }

        public void setAutoScroll(boolean isAuto) {
            mIsAutoScroll = isAuto;
        }

        @Override
        public void scrollRectToVisible(Rectangle aRect) {
            if (mIsAutoScroll) {
                super.scrollRectToVisible(aRect);
            }
        }
    }

    private Box newControlFirstLine() {
        Box control1 = new Box(BoxLayout.X_AXIS);
        control1.add(Box.createHorizontalStrut(10));

        control1.add(new JLabel("Highlight: "));
        setTextFieldOnChange(mHighlightTF = new JFilterTextField(20, mFilterLen));
        control1.add(mHighlightTF);
        control1.add((newClearTextButton(mHighlightTF)));
        control1.add(Box.createHorizontalStrut(10));

        Log.LogLevel[] levels = Log.LogLevel.values();
        final int levelSize = levels.length - 1;
        ButtonGroup levelGroup = new ButtonGroup();
        mLogLevelRadioBtns = new JRadioButton[levelSize];
        for (int i = 0; i < mLogLevelRadioBtns.length; i++) {
            Log.LogLevel level = levels[i];
            JRadioButton rb = new JRadioButton(level.getPriorityLetter() + "  ");
            Color color = LogAttr.getLogLevelColor(level);
            rb.setBackground(color);
            rb.setForeground(color);
            rb.setBorderPainted(true);
            rb.addActionListener(mLevelChangeAction);
            rb.setBorder(BorderFactory.createEtchedBorder());
            levelGroup.add(mLogLevelRadioBtns[i] = rb);
        }
        mLogLevelRadioBtns[0].setSelected(true);

        JPanel logLevelPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gBC = new GridBagConstraints();
        gBC.fill = GridBagConstraints.BOTH;
        for (gBC.gridx = 0; gBC.gridx < levelSize; gBC.gridx++) {
            logLevelPanel.add(mLogLevelRadioBtns[gBC.gridx], gBC);
        }

        mAutoScrollCB = new JCheckBox("Auto Scroll");
        mAutoScrollCB.setMargin(new Insets(0, 10, 0, 10));
        mAutoScrollCB.setSelected(true);
        mAutoScrollCB.addActionListener(e -> mLogPane.setAutoScroll(mAutoScrollCB.isSelected()));
        mLogPane.setAutoScroll(mAutoScrollCB.isSelected());
        logLevelPanel.add(mAutoScrollCB);
        control1.add(logLevelPanel);

        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> mLogPane.setText(""));
        control1.add(clear);

        final String pauseStr = "  Pause  ";
        final JButton pause = new JButton(pauseStr);
        pause.addActionListener(e -> {
            if (pause.getText().equals(pauseStr)) {
                mPause = true;
                pause.setText("Resume");
            } else {
                mPause = false;
                pause.setText(pauseStr);
            }
        });
        control1.add(pause);

        return control1;
    }

    private Box newControlSecondLine() {
        Box control2 = new Box(BoxLayout.X_AXIS);
        control2.add(Box.createHorizontalStrut(10));

        setTextFieldOnChange(mIncludeTF = new JFilterTextField(20, mFilterLen, true));
        control2.add(new JLabel("include: "));
        control2.add(mIncludeTF);
        control2.add(newClearTextButton(mIncludeTF));
        control2.add(Box.createHorizontalStrut(10));

        setTextFieldOnChange(mExcludeTF = new JFilterTextField(20, mFilterLen, true));
        control2.add(new JLabel("exclude: "));
        control2.add(mExcludeTF);
        control2.add(newClearTextButton(mExcludeTF));
        control2.add(Box.createHorizontalStrut(10));
        return control2;
    }

    JButton newClearTextButton(final JTextField tf) {
        JButton btn = new JButton("X");
        Border margin = new EmptyBorder(5, 10, 5, 10);
        btn.setBorder(margin);
        btn.addActionListener(e -> {
            String t = tf.getText();
            if (t != null && t.length() > 0) {
                tf.setText("");
                restart();
            }
        });

        return btn;
    }

    private final Action mLevelChangeAction = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            mLevel = Log.LogLevel.getByLetter(e.getActionCommand().charAt(0));
            restart();
        }
    };

    JFilterTextField.TimerTaskCreator mRefreshAction = () -> new TimerTask() {
        @Override
        public void run() {
            restart();
        }
    };

    private void setTextFieldOnChange(JFilterTextField tf) {
        tf.setChangeTask(mControlTimer, mRefreshAction);
    }

    private abstract static class TaskPool<T extends TaskPool<T>.Task> {
        private final LinkedList<T> tasks = new LinkedList<>();
        private int limit = 256;

        void preAllocate(int preSize) {
            limit = preSize;
            for (int i = 0; i < limit; i++) {
                tasks.add(create());
            }
        }

        abstract class Task implements Runnable {
            @Override
            public void run() {
                runTask();
                recycle();
            }

            abstract void runTask();

            @SuppressWarnings("unchecked")
            private void recycle() {
                synchronized (tasks) {
                    if (tasks.size() < limit) {
                        tasks.addLast((T) this);
                    }
                }
            }
        }

        abstract T create();

        T pick() {
            synchronized (tasks) {
                if (!tasks.isEmpty()) {
                    return tasks.pop();
                }
            }
            return create();
        }
    }

    private static class UpdatePaneTaskUtil extends TaskPool<UpdatePaneTaskUtil.UpdatePaneTask> {
        final static int BUSY_THRESHOLD = 300;
        final AtomicInteger mEventCount = new AtomicInteger(0);
        final ArrayList<String> mBusyBuf = new ArrayList<>(256);
        final LogFrame mViewer;

        class UpdatePaneTask extends TaskPool<UpdatePaneTask>.Task {
            private String mLine;

            @Override
            void runTask() {
                mViewer.appendLine(mLine);
                mLine = null;
                int remain = mEventCount.decrementAndGet();
                if (remain == 0 && !mBusyBuf.isEmpty()) {
                    //System.out.println("b " + mBusyBuf.size());
                    for (int i = 0, s = mBusyBuf.size(); i < s; i++) {
                        mViewer.appendLine(mBusyBuf.get(i));
                    }
                    mBusyBuf.clear();
                }
            }
        }

        private UpdatePaneTaskUtil(LogFrame viewer) {
            preAllocate(2048);
            mViewer = viewer;
        }

        @Override
        UpdatePaneTask create() {
            return new UpdatePaneTask();
        }

        private void appendLogLine(String l) {
            UpdatePaneTask t = pick();
            t.mLine = l;
            int remain = mEventCount.get();
            if (remain > BUSY_THRESHOLD) {
                mBusyBuf.add(l);
            } else {
                mEventCount.incrementAndGet();
                SwingUtilities.invokeLater(t);
            }
        }
    }
}

class LogAttr {

    enum LogSource {
        defaults("default", "", true),
        system("system", "-b system", true),
        main("main", "-b main", true),
        events("events", "-b events", false),
        kernel("kernel", "", false),
        klogcat("klogcat", "", false),
        radio("radio", "-b radio", true),
        crash("crash", "-b crash", true),
        system_events("system & event", "-b system -b events", true),
        system_main_events("system & main & event", "-b system -b main -b events", true),
        all("all", "-b all", true);

        LogSource(String n, String b, boolean l) {
            name = n;
            cmd = b;
            hasLevel = l;
        }

        public final String name;
        public final String cmd;
        public final boolean hasLevel;

        public String getCommand() {
            if (this == kernel) {
                return " cat /proc/kmsg ";
            } else if (this == klogcat) {
                return " klogcat ";
            }
            return " logcat " + cmd + " -v threadtime";
        }

        final static HashMap<String, LogSource> srcMapping = new HashMap<>();

        static {
            for (LogSource s : LogSource.values()) {
                srcMapping.put(s.name, s);
            }
        }

        public static LogSource getByName(String n) {
            return srcMapping.get(n);
        }
    }

    final static Color COLOR_GREEN = new Color(0, 150, 0);
    final static Color COLOR_ORANGE = new Color(230, 140, 0);

    public static Color getLogLevelColor(Log.LogLevel level) {
        switch (level) {
            case DEBUG:
                return Color.BLUE;
            case INFO:
                return COLOR_GREEN;
            case WARN:
                return COLOR_ORANGE;
            case ERROR:
                return Color.RED;
            case ASSERT:
            case VERBOSE:
            default:
                return Color.BLACK;
        }
    }

}
