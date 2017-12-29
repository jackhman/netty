/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec;

import io.netty.util.Recycler;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.MathUtil;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.RandomAccess;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Special {@link AbstractList} implementation which is used within our codec base classes.
 */
final class CodecOutputList extends AbstractList<Object> implements RandomAccess {

    private static final Recycler<CodecOutputList> RECYCLER = new Recycler<CodecOutputList>() {
        @Override
        protected CodecOutputList newObject(Handle<CodecOutputList> handle) {
            // Size of 16 should be good enough for 99 % of all users.
            return new CodecOutputList(handle, 16);
        }
    };

    private static final Recycler.Handle<CodecOutputList> NOOP_RECYCLER  = new Recycler.Handle<CodecOutputList>() {
        @Override
        public void recycle(CodecOutputList object) {
            // drop on the floor
        }
    };

    private static final FastThreadLocal<CodecOutputListPool> DEQUE_FAST_THREAD_LOCAL =
            new FastThreadLocal<CodecOutputListPool>() {
                @Override
                protected CodecOutputListPool initialValue() throws Exception {
                    return new DefaultCodecOutputListPool(8);
                }
            };

    private static final FastThreadLocal<CodecOutputListPool> SPECIAL_POOL_FAST_THREAD_LOCAL =
            new FastThreadLocal<CodecOutputListPool>() {
                @Override
                protected CodecOutputListPool initialValue() throws Exception {
                    return new SpecialCodecOutputListPool(16);
                }
            };

    static CodecOutputList newLocalInstance() {
        return DEQUE_FAST_THREAD_LOCAL.get().get();
    }

    static CodecOutputList newLocalSpecialInstance() {
        return SPECIAL_POOL_FAST_THREAD_LOCAL.get().get();
    }

    private interface CodecOutputListPool extends Recycler.Handle<CodecOutputList> {
        CodecOutputList get();
    }

    private static final class DefaultCodecOutputListPool extends ArrayDeque<CodecOutputList>
            implements CodecOutputListPool {

        DefaultCodecOutputListPool(int numElements) {
            super(numElements);
            for (int i = 0; i < numElements; ++i) {
                // Size of 16 should be good enough for 99 % of all users.
                offerLast(new CodecOutputList(this, 16));
            }
        }

        @Override
        public CodecOutputList get() {
            CodecOutputList outList = pollLast();
            return outList == null ? newNonCachedInstance() : outList;
        }

        @Override
        public void recycle(CodecOutputList object) {
            offerLast(object);
        }
    }

    private static final class SpecialCodecOutputListPool
            implements CodecOutputListPool {

        private final CodecOutputList[] elements;
        private final int mask;

        private int currentIdx;
        private int count;

        SpecialCodecOutputListPool(int numElements) {
            elements = new CodecOutputList[MathUtil.safeFindNextPositivePowerOfTwo(numElements)];
            for (int i = 0; i < elements.length; ++i) {
                // Size of 16 should be good enough for 99 % of all users.
                elements[i] = new CodecOutputList(this, 16);
            }
            count = elements.length;
            currentIdx = elements.length;
            mask = elements.length - 1;
        }

        @Override
        public CodecOutputList get() {
            if (count == 0) {
                return newNonCachedInstance();
            }
            --count;

            int idx = (currentIdx - 1) & mask;
            CodecOutputList list = elements[idx];
            currentIdx = idx;
            return list;
        }

        @Override
        public void recycle(CodecOutputList object) {
            int idx = currentIdx;
            elements[idx] = object;
            currentIdx = (idx + 1) & mask;
            ++count;
            assert count <= elements.length;
        }
    }

    static CodecOutputList newInstance() {
        return newLocalSpecialInstance();
    }

    static CodecOutputList newRecyclerInstance() {
        return RECYCLER.get();
    }

    static CodecOutputList newNonCachedInstance() {
        return new CodecOutputList(NOOP_RECYCLER, 4);
    }

    private final Recycler.Handle<CodecOutputList> handle;
    private int size;
    private Object[] array;
    private boolean insertSinceRecycled;

    CodecOutputList(Recycler.Handle<CodecOutputList> handle, int size) {
        this.handle = handle;
        array = new Object[size];
    }

    @Override
    public Object get(int index) {
        checkIndex(index);
        return array[index];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean add(Object element) {
        checkNotNull(element, "element");
        try {
            insert(size, element);
        } catch (IndexOutOfBoundsException ignore) {
            // This should happen very infrequently so we just catch the exception and try again.
            expandArray();
            insert(size, element);
        }
        ++ size;
        return true;
    }

    @Override
    public Object set(int index, Object element) {
        checkNotNull(element, "element");
        checkIndex(index);

        Object old = array[index];
        insert(index, element);
        return old;
    }

    @Override
    public void add(int index, Object element) {
        checkNotNull(element, "element");
        checkIndex(index);

        if (size == array.length) {
            expandArray();
        }

        if (index != size - 1) {
            System.arraycopy(array, index, array, index + 1, size - index);
        }

        insert(index, element);
        ++ size;
    }

    @Override
    public Object remove(int index) {
        checkIndex(index);
        Object old = array[index];

        int len = size - index - 1;
        if (len > 0) {
            System.arraycopy(array, index + 1, array, index, len);
        }
        array[-- size] = null;

        return old;
    }

    @Override
    public void clear() {
        // We only set the size to 0 and not null out the array. Null out the array will explicit requested by
        // calling recycle()
        size = 0;
    }

    /**
     * Returns {@code true} if any elements where added or set. This will be reset once {@link #recycle()} was called.
     */
    boolean insertSinceRecycled() {
        return insertSinceRecycled;
    }

    /**
     * Recycle the array which will clear it and null out all entries in the internal storage.
     */
    void recycle() {
        for (int i = 0 ; i < size; i ++) {
            array[i] = null;
        }
        clear();
        insertSinceRecycled = false;
        handle.recycle(this);
    }

    /**
     * Returns the element on the given index. This operation will not do any range-checks and so is considered unsafe.
     */
    Object getUnsafe(int index) {
        return array[index];
    }

    private void checkIndex(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void insert(int index, Object element) {
        array[index] = element;
        insertSinceRecycled = true;
    }

    private void expandArray() {
        // double capacity
        int newCapacity = array.length << 1;

        if (newCapacity < 0) {
            throw new OutOfMemoryError();
        }

        Object[] newArray = new Object[newCapacity];
        System.arraycopy(array, 0, newArray, 0, array.length);

        array = newArray;
    }
}
