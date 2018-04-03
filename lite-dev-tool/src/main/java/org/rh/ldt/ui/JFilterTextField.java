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

import javax.swing.AbstractAction;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.util.regex.Pattern;

public class JFilterTextField extends JTextField implements ContextMenuUtil.AutoContextMenu {

    private final static Color sRegExpMode = new Color(200, 250, 200);
    private Runnable mOnLengthSatisfiedAction, mOnEmpty;
    private String mCurrentLowerCaseStr = "";
    private String[] mCurrentSepBySpace;
    private boolean mIsRegExpMode;
    private Pattern mRegExp;
    private int mPreviousLen;
    private int mFilterLen;
    private String mPreviousText;

    public JFilterTextField(int columns, int filterLen) {
        this(columns, filterLen, false);
    }

    public JFilterTextField(int columns, int filterLen, boolean supportRegexp) {
        super(columns);
        mFilterLen = filterLen;

        if (supportRegexp) {
            setToolTipText("Press ctrl + R to enter regular expression mode");
            KeyStroke ctrlR = KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.CTRL_MASK);
            getInputMap().put(ctrlR, "ctrlR");
            getActionMap().put("ctrlR", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    mIsRegExpMode = !mIsRegExpMode;
                    if (mIsRegExpMode) {
                        setBackground(sRegExpMode);
                    } else {
                        setBackground(Color.WHITE);
                    }
                    update();
                }
            });
        }

        setDocument(new javax.swing.text.PlainDocument() {
            private static final int limit = 128;

            @Override
            public void insertString(int offs, String str, javax.swing.text.AttributeSet a)
                    throws javax.swing.text.BadLocationException {
                if (str != null && (getLength() + str.length()) <= limit) {
                    super.insertString(offs, str, a);
                }
            }
        });

        getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update();
            }
        });
    }

    private void update() {
        String text = getText();
        if (mOnEmpty != null && (text == null || text.length() == 0) &&
                (mPreviousText != null && mPreviousText.length() > 0)) {
            mOnEmpty.run();
        }
        if (text == null || text.equals(mPreviousText)) {
            return;
        }
        if (mIsRegExpMode) {
            try {
                mRegExp = Pattern.compile(text);
            } catch (Exception syntaxE) {
                mRegExp = null;
            }
        }
        mCurrentLowerCaseStr = text.toLowerCase();
        mCurrentSepBySpace = mCurrentLowerCaseStr.split(" ");

        int len = text.length();
        if (mOnLengthSatisfiedAction != null) {
            if (len > mFilterLen) {
                mOnLengthSatisfiedAction.run();
            } else if (mPreviousLen > len && mPreviousLen == mFilterLen + 1) {
                mOnLengthSatisfiedAction.run();
            }
        }
        mPreviousLen = len;
        mPreviousText = text;
    }

    interface TimerTaskCreator {
        java.util.TimerTask createTask();
    }

    public void setChangeTask(final java.util.Timer timer, final TimerTaskCreator task) {
        Runnable onChange = new Runnable() {
            java.util.TimerTask mLastRefreshTask;
            @Override
            public void run() {
                if (mLastRefreshTask != null) {
                    mLastRefreshTask.cancel();
                }
                timer.purge();
                timer.schedule(mLastRefreshTask = task.createTask(), 300);
            }
        };
        setOnLengthSatisfiedListener(onChange);
        setOnEmpty(onChange);
    }

    public boolean isRegExpMode() {
        return mIsRegExpMode;
    }

    public boolean match(String text) {
        return mRegExp != null && mRegExp.matcher(text).find();
    }

    public int getLength() {
        return getDocument().getLength();
    }

    public String getCurrentLowerCaseStr() {
        return mCurrentLowerCaseStr;
    }

    public String[] getCurrentSepBySpace() {
        return mCurrentSepBySpace;
    }

    public void setOnLengthSatisfiedListener(Runnable action) {
        mOnLengthSatisfiedAction = action;
    }

    public void setOnEmpty(Runnable action) {
        mOnEmpty = action;
    }
}
