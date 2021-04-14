/*
 * Copyright 2012 The Netty Project
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

package io.netty.buffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

	//所在的arena区域
    final PoolArena<T> arena;
	//真正分配的内存，如果是堆内的话就是字节数组，否则就是直接缓冲区DirectByteBuffer，
	//这个是真正操作分配的内存，其他的一些都是逻辑上分配内存
    final T memory;
    final boolean unpooled;//是否要进行池化
    final int offset;//缓存行偏移，默认0
    private final byte[] memoryMap;//内存映射深度字节数组
    private final byte[] depthMap;//深度映射字节数组，这个数组不变，作为对照计算的
    private final PoolSubpage<T>[] subpages;//子页数组，也是满二叉树的叶子节点数组
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;//跟前面讲过的一样，这个用来判断是否小于页大小
    private final int pageSize;//页大小 8k
    private final int pageShifts;//页位移，也就是pageSize=1<<<pageShifts,8k就是13，即2的13次方是8k
    private final int maxOrder;//最大深度索引，默认11 从0开始的
    private final int chunkSize;//块大小，默认16m
    private final int log2ChunkSize;//ChunkSize取log2的值 24
    private final int maxSubpageAllocs;//最大子叶数，跟最大深度有关，最大深度上的叶子结点个数就是子页数
    /** Used to mark memory as unusable */
    private final byte unusable;//是否无法使用，最大深度索引+1，默认是12，表示不可用

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;//池化用

    private int freeBytes;//可分配的字节数，默认是16m

    PoolChunkList<T> parent;//所在的块列表
    PoolChunk<T> prev;//前驱
    PoolChunk<T> next;//后继

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;//池化
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;//8k
        this.pageShifts = pageShifts;//13
        this.maxOrder = maxOrder;//11
        this.chunkSize = chunkSize;//16m
        this.offset = offset;//0
        unusable = (byte) (maxOrder + 1);//最大深度索引+1，表示不可用 默认是11+1=12
        log2ChunkSize = log2(chunkSize);//chunkSize是2的多少次数 24次
        subpageOverflowMask = ~(pageSize - 1);//比大小的掩码
        freeBytes = chunkSize;//初始可分配就是块大小16m

		//最大深度应该小于30
        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;//可分配子页的个数 2的11次方=2048，是最大深度索引为maxOrder的二叉树的个数，也就是满二叉树的叶子节点

        // Generate the memory map.
		// 满二叉树的数组 4095个 总共有12层 根据等比公式 结果为4095个
        memoryMap = new byte[maxSubpageAllocs << 1];//叶子结点的两倍
        depthMap = new byte[memoryMap.length];//参照深度，固定的
        int memoryMapIndex = 1;//内存映射索引从1开始 到4095 总共4095个 第0个索引不用
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time//深度索引从0开始到maxOrder
            int depth = 1 << d;//深度为d的层上有depth个结点，depth是某一深度索引d的结点个数
            for (int p = 0; p < depth; ++ p) {//从左到右，从上到下，进行编号，从1开始，并且设置深度索引d
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;//设置深度索引
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;//内存映射索引+1
            }
        }

        //分配子页个数
		//叶子节点的数组2048个，每个容量是8k
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);//创建性能比较好的队列
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;//一个唯一的值，根据叶子节点id，位图索引生成
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize 大于页大小
        	//大于页大小的分配，即Normal类型
            handle =  allocateRun(normCapacity);//run来分配
        } else {
            handle = allocateSubpage(normCapacity);//子页来分 <pageSize
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;//从缓存的NIO缓冲区队列里取
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {//从id开始直到跟节点
            int parentId = id >>> 1;//获取父节点
            byte val1 = value(id);//获id节点的深度索引值
            byte val2 = value(id ^ 1);//获取另一个节点的深度索引值，即是左节点就获取右节点，是右节点就获取左节点
            byte val = val1 < val2 ? val1 : val2;//取最小的
            setValue(parentId, val);//设置父节点的深度索引值为子节点最小的那一个
            id = parentId;//继续遍历父节点
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    // 修改节点对应的层数，底下这个方法涉及了很多的位运算，这里大家要仔细琢磨
    private int allocateNode(int d) {
        int id = 1;//从内存映射索引为1的开始 也就是深度索引为0开始
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1 // 用于比较id所在深度索引是否小于d
        byte val = value(id);//获取内存映射的深度值  // memoryMap[id] , 一般1对应的层数是 0 ,当第一个节点被分配完成后，这个节点的值会变成12
        if (val > d) { // unusable // unusable 大于此深度索引就不可用了
            return -1;
        }//从头开始深度优先，遍历完所有深度索引小于d的可用的子结点，只有到id的深度索引是d的时候才会结束，
		// 而且是遍历一次都是深度索引+1，即是深度优先的算法，先找出对应的深度，然后从左到右看是否有内存可分配。
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;//得到下一深度索引的左节点
            val = value(id);//获取对应深度索引值
            if (val > d) {//如果大于深度索引 即左节点不能用了
                id ^= 1;//异或位运算，获取右结点
                val = value(id);//再取出对应深度索引值
            }
        }
        byte value = value(id);
		//获取深度索引值，这里的value>=d 下面还要断言，如果是=d才是可以用的，>d即被设置了unusable，表示不可用了
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);//断言id保存的深度索引值为d 且id所在深度索引为d，否则就会输出错误信息
        setValue(id, unusable); // mark as unusable 设置id深度索引值，为最大深度索引+1，即不可用了
        updateParentsAlloc(id);//更新父节点值
        return id;//返回内存映射索引
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
		//这里的容量都是pageSize及以上的，log2(normCapacity) - pageShifts 表示容量是页大小的2的多少倍，
		// 最大深度索引再减去这个，刚好是定位到页大小倍数的深度索引
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        //取出内存映射索引的关键，核心的分配算法
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);//减去分配的大小
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
		//获取子页数组的头结点，可以是ting数组也可以是small数组的
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);// 根据请求内存大小，获取下标，然后获取对应的subPage
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves // 最大层数 11
        synchronized (head) {//修改数据要同步
            int id = allocateNode(d);//找到能分配的内存映射索引 //分配节点，修改涉及节点层数, 具体看下面的分析
            if (id < 0) {// 无可分配节点
                return id;//没找到
            }

            final PoolSubpage<T>[] subpages = this.subpages;//满二叉树的叶子节点数组
            final int pageSize = this.pageSize;//8k

			//减少一页大小的可用字节
            freeBytes -= pageSize; // 16M（初始化值，后面会逐渐减少） - 8K

			//获取子页数组索引，其实跟取模一样 0-2047
            int subpageIdx = subpageIdx(id); // 2048 对应的偏移量是 0 ， 2049 对应的偏移量是 1 。。。4095 对应偏移量是 2047
            PoolSubpage<T> subpage = subpages[subpageIdx];//获取子页，第一次是空的 // 取对应下标的 subpage
            if (subpage == null) {//第一次是空
            	//runOffset(id) 获得id节点对应的内存在块chunk中的偏移量，或者说起始地址
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);// 创建PoolSubpage
                subpages[subpageIdx] = subpage;//添加进子页数组
				// 新建或初始化subpage并加入到chunk的subpages数组，同时将subpage加入到arena的subpage双向链表中，最后完成分配请求的内存。
				// 代码中，subpage != null的情况产生的原因是：subpage初始化后分配了内存，但一段时间后该subpage分配的内存释放并从arena的双向链表中删除，
				// 此时subpage不为null，当再次请求分配时，只需要调用init()将其加入到areana的双向链表中即可。
            } else {
                subpage.init(head, normCapacity);//重新初始化
            }
            return subpage.allocate(); //最后结果是一个long整数，其中低32位表示二叉树中的分配的节点，高32位表示subPage中分配的具体位置
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);//低32位 即内存映射索引
        int bitmapIdx = bitmapIdx(handle);//这里获取的如果是子页的handle,bitmapIdx不为0，那高32位，并不是真正的位图索引，最高非符号位多了1，如果是normal的，那就是0
        if (bitmapIdx == 0) {//normal只有id，没有前面的子页相关的位图信息
			//获取对应编号的深度索引
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {//子页初始化
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);//获取内存映射索引

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];//获取对应的子页
        assert subpage.doNotDestroy;//还没销毁
        assert reqCapacity <= subpage.elemSize;//请求容量不会大于规范后的

		//池化字节缓冲区初始化
        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,//块的偏移地址+页的偏移地址+缓存行的偏移地址(默认0)
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);//用这种位运算代替直接取log，提高性能
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);//深度值越小，说明能分配的越多，越大能分配的越少
    }

	// 计算内存偏移 即内存映射索引id对应的节点在块chunk中的偏移量 范围是[0 - chunkSize)，
	// 比如2048是0 x 8k=0 2049是1 x 8k=8k 1024是0 x 16k=0 1025是1 x 16k=16k
    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
		// 其实每一个节点的偏移位置就是他的左节点的偏移，叶子节点就是自身的偏移量
        int shift = id ^ 1 << depth(id);//节点的偏移位数 比如2048是0 2049是1 2050是2 ..4095是2047 也就是某个深度中从左到右的编号，从0开始。
        return shift * runLength(id);//偏移量=偏移位数x可分配内存大小
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

	//获取低32位的handle，即id
    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

	//直接获取handle高32位，子页的并非原始的bitmapIdx
    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
