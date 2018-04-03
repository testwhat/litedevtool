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

import java.util.Arrays;

public final class IntArray {
    private int lastIndex = -1;
    private int[] data;

    public IntArray() {
        this(10);
    }

    public IntArray(int initSize) {
        data = new int[initSize];
    }

    public void ensureCapacity(int minCapacity) {
        int oldCapacity = data.length;
        if (minCapacity > oldCapacity) {
            int newCapacity = (oldCapacity * 3) / 2 + 1;
            if (newCapacity < minCapacity) {
                newCapacity = minCapacity;
            }
            data = Arrays.copyOf(data, newCapacity);
        }
    }

    public void add(int c) {
        ensureCapacity(++lastIndex + 1);
        data[lastIndex] = c;
    }

    public int size() {
        return lastIndex + 1;
    }

    public int get(int index) {
        return data[index];
    }

    public void set(int index, int value) {
        data[index] = value;
    }

    public boolean contains(int v) {
        for (int val : data) {
            if (val == v) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        lastIndex = -1;
    }

    public boolean remove(int index) {
        if (lastIndex - index >= 0) {
            System.arraycopy(data, index + 1, data, index, lastIndex - index);
            lastIndex--;
            return true;
        }
        return false;
    }

    public boolean removeTarget(int value) {
        int i = 0;
        for (; i <= lastIndex; i++) {
            if (data[i] == value) {
                break;
            }
        }
        return remove(i);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < lastIndex; i++) {
            sb.append(data[i]).append(',');
        }
        sb.append(data[lastIndex]).append("}");
        return sb.toString();
    }

    public int[] toArray() {
        int[] a = new int[size()];
        System.arraycopy(data, 0, a, 0, a.length);
        return a;
    }

}
