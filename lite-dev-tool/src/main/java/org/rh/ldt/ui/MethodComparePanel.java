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
import org.rh.ldt.DexDiff;
import org.rh.ldt.util.StringUtil;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

public class MethodComparePanel extends JPanel {

    private final JCheckBox mListDfCb = new JCheckBox(
            "List filenames which have differences methods");
    private final String[] mDiffDex = new String[2];

    public static JPanel create() {
        MethodComparePanel diffP = new MethodComparePanel();
        return diffP;
    }

    private MethodComparePanel() {
        super(new GridBagLayout());
        add(newCompareDropPanel("Drop left dex/dexjar/apk", 0), newGbc(0, 0));
        add(newCompareDropPanel("Drop right dex/dexjar/apk", 1), newGbc(0, 1));

        final JTextArea keywordTA = new JTextArea();
        Box diffCtl = Box.createHorizontalBox();
        JButton run = new JButton("    Run    ");
        run.addActionListener(e -> {
            final String kwt = keywordTA.getText();
            final DexDiff.Param[] res = new DexDiff.Param[1];
            MainUi.execTask(new MainUi.Task() {
                @Override
                public void run() {
                    res[0] = diffDex(kwt == null ? null : kwt.split("[ \r\n]+"));
                }

                @Override
                public void done() {
                    showDiffResult(res[0]);
                }
            });
        });
        diffCtl.add(run);
        diffCtl.add(Box.createHorizontalStrut(20));
        diffCtl.add(mListDfCb);

        GridBagConstraints gbc = newGbc(1, 0);
        gbc.gridwidth = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        add(diffCtl, gbc);

        Box diffBox = Box.createVerticalBox();
        JLabel keywordL = new JLabel("Source name filter, e.g. Activity.java, "
                + "empty=compare all classes, separate by space or new line");
        diffBox.add(keywordL);
        diffBox.add(new JScrollPane(keywordTA));
        gbc = newGbc(2, 0);
        gbc.gridwidth = 2;
        add(diffBox, gbc);
    }

    final Droppable.Panel newCompareDropPanel(String title, final int index) {
        Droppable.Panel p = new Droppable.Panel(new BorderLayout()) {
            JLabel selFile = new JLabel();
            {
                JScrollPane sp = new JScrollPane(selFile);
                sp.setBorder(BorderFactory.createEmptyBorder());
                sp.setPreferredSize(new Dimension(0, 0));
                add(sp);
            }

            @Override
            public void onDrop(File[] files) {
                File f = files[0];
                if (!f.isFile()) {
                    selFile.setText("Not allow non-file " + f);
                    return;
                }
                mDiffDex[index] = f.getAbsolutePath();
                selFile.setText(mDiffDex[index]);
            }
        };
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    static GridBagConstraints newGbc(int r, int c) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = c;
        gbc.gridy = r;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    void showDiffResult(final DexDiff.Param param) {
        if (param == null) {
            DLog.i("No diff result");
            return;
        }
        JFrame frame = new JFrame("Dex APIs differences");
        final JSplitPane splitPane = new JSplitPane();
        splitPane.add(new JScrollPane(newDiffTextArea(param.w1.toString())), JSplitPane.LEFT);
        splitPane.add(new JScrollPane(newDiffTextArea(param.w2.toString())), JSplitPane.RIGHT);
        splitPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                splitPane.setDividerLocation(0.5);
            }
        });
        frame.add(splitPane);
        frame.setBounds(100, 100, 1100, 600);
        frame.setVisible(true);
    }

    private JTextArea newDiffTextArea(String content) {
        final JTextArea t = new JTextArea(content);
        t.setTabSize(4);
        t.addMouseListener(new MouseAdapter() {
            final JPopupMenu popMenu = new JPopupMenu();
            {
                addMenuItem(popMenu, "Copy",
                        e -> StringUtil.copyToClipboard(t.getSelectedText()));
                addMenuItem(popMenu, "Copy all",
                        e -> StringUtil.copyToClipboard(t.getText()));
            }

            private void addMenuItem(JComponent menu, String text, ActionListener action) {
                JMenuItem mi = new JMenuItem(text);
                mi.addActionListener(action);
                menu.add(mi);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON3) {
                    popMenu.show(t, e.getX(), e.getY());
                }
            }
        });
        return t;
    }

    @Nullable
    final DexDiff.Param diffDex(String[] kws) {
        for (String f : mDiffDex) {
            if (f == null || f.length() < 2) {
                DLog.i("diffDex: Invalid input");
                return null;
            }
        }
        DexDiff.Param param = new DexDiff.Param();
        param.outputFilename = mListDfCb.isSelected();
        param.dexF1 = mDiffDex[0];
        param.dexF2 = mDiffDex[1];
        param.w1 = new StringWriter();
        param.w2 = new StringWriter();
        param.keywords = kws;
        try {
            DexDiff.diff(param);
        } catch (IOException e) {
            DLog.ex(e);
        }
        return param;
    }
}
