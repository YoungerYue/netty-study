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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.MathUtil;

import java.util.AbstractList;
import java.util.RandomAccess;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Special {@link AbstractList} implementation which is used within our codec base classes.
 */
final class CodecOutputList extends AbstractList<Object> implements RandomAccess {

    private static final CodecOutputListRecycler NOOP_RECYCLER = new CodecOutputListRecycler() {
        @Override
        public void recycle(CodecOutputList object) {
            // drop on the floor and let the GC handle it.
        }
    };

    //线程本地变量就可以跟着线程一起存活着，等于有了池化的作用，而且是线程安全的，存在IO线程里
    private static final FastThreadLocal<CodecOutputLists> CODEC_OUTPUT_LISTS_POOL =
            new FastThreadLocal<CodecOutputLists>() {
                @Override
                protected CodecOutputLists initialValue() throws Exception {
                    // 16 CodecOutputList per Thread are cached.
                    return new CodecOutputLists(16);
                }
            };

    //回收器接口，然后创建了一个啥都没干的回收器，等GC处理了
    private interface CodecOutputListRecycler {
        void recycle(CodecOutputList codecOutputList);
    }

	/**
	 * 里面放着很多CodecOutputList，然后实现了回收器接口CodecOutputListRecycler，用来回收CodecOutputList。
	 * 内部创建了一个CodecOutputList数组，默认每个CodecOutputList可以存放16个消息对象。
	 * 如果获取的时候没有CodecOutputList了，就会创建一个不缓存的CodecOutputList，默认存放4个消息对象。
	 */
	private static final class CodecOutputLists implements CodecOutputListRecycler {
        private final CodecOutputList[] elements;
        private final int mask;//取余掩码

        private int currentIdx;//当前索引
        private int count;//列表个数

        CodecOutputLists(int numElements) {
            elements = new CodecOutputList[MathUtil.safeFindNextPositivePowerOfTwo(numElements)];//创建2的幂次个列表
            for (int i = 0; i < elements.length; ++i) {//初始化
                // Size of 16 should be good enough for the majority of all users as an initial capacity.
                elements[i] = new CodecOutputList(this, 16);
            }
            count = elements.length;
            currentIdx = elements.length;
            mask = elements.length - 1;
        }

        ////如果没缓存就创建一个不缓存的，默认创建长度为4的数组
        public CodecOutputList getOrCreate() {
            if (count == 0) {
                // Return a new CodecOutputList which will not be cached. We use a size of 4 to keep the overhead
                // low.
                return new CodecOutputList(NOOP_RECYCLER, 4);
            }
            --count;

            int idx = (currentIdx - 1) & mask;//从后往前取，取模，算出索引位置
            CodecOutputList list = elements[idx];
            currentIdx = idx;
            return list;
        }

		//回收CodecOutputList
        @Override
        public void recycle(CodecOutputList codecOutputList) {
            int idx = currentIdx;
            elements[idx] = codecOutputList;
            currentIdx = (idx + 1) & mask;//当前索引增加，取模
            ++count;
            assert count <= elements.length;
        }
    }

    //获取CodecOutputList对象
	//外面是通过CodecOutputList 的newInstance来获得对象，其实是从线程本地变量的CodecOutputLists里获取的
    static CodecOutputList newInstance() {
        return CODEC_OUTPUT_LISTS_POOL.get().getOrCreate();
    }

    private final CodecOutputListRecycler recycler;//回收器，这个是跟线程本地变量挂钩的，就是一个池化的作用
    private int size;//拥有的对象个数
    private Object[] array;//对象数组
    private boolean insertSinceRecycled;//是否有对象加入数组过

	//构造方法的时候就会传入回收器和消息对象数组的长度
    private CodecOutputList(CodecOutputListRecycler recycler, int size) {
        this.recycler = recycler;
        array = new Object[size];
    }

    //有检查的获取，外部使用
    @Override
    public Object get(int index) {
        checkIndex(index);
        return array[index];
    }

    @Override
    public int size() {
        return size;
    }

    //添加对象到最后
	//直接插入，如果越界就扩容，再插入
    @Override
    public boolean add(Object element) {
        checkNotNull(element, "element");
        try {
            insert(size, element);//插入
        } catch (IndexOutOfBoundsException ignore) {
            // This should happen very infrequently so we just catch the exception and try again.
            expandArray();//扩容
            insert(size, element);//插入
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

        if (index != size) {
            System.arraycopy(array, index, array, index + 1, size - index);
        }

        insert(index, element);
        ++ size;
    }

    //remove删除指定位置的对象
	//把指定位置的对象取出来，然后移动数组，最后位置清空，并返回删除的对象
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

    //只是把个数清空了。真正的删除元素是在recycle中的。
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
    //recycle清空对象并回收到CodecOutputLists中
    void recycle() {
        for (int i = 0 ; i < size; i ++) {
            array[i] = null;
        }
        size = 0;
        insertSinceRecycled = false;

        recycler.recycle(this);
    }

    /**
     * Returns the element on the given index. This operation will not do any range-checks and so is considered unsafe.
     */
    //getUnsafe无检查获取消息对象
	//少了检查，内部使用的
    Object getUnsafe(int index) {
        return array[index];
    }

    private void checkIndex(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    //插入对象到指定位置，设置标记
    private void insert(int index, Object element) {
        array[index] = element;
        insertSinceRecycled = true;//有放入过了
    }

    //扩容两倍，直至溢出
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
