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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

	//大小类型 还有超过chunkSize的，huge
    enum SizeClass {
        Tiny,//[16-512)
        Small,//[512 - pageSize)
        Normal//[pageSize - chunkSize]
    }

	//TinySubpage个数32 tiny是512以下的，512除以16 为32， 第1个是个头结点 后面31个才是有用的
    static final int numTinySubpagePools = 512 >>> 4;

    final PooledByteBufAllocator parent;

    private final int maxOrder;//二叉树的最大深度 默认11
    final int pageSize;//页大小 默认8K
    final int pageShifts;//1左移多少位得到pageSize，页大小是8k = 1 << 13 所以默认13位
    final int chunkSize;//块大小 默认16m
    final int subpageOverflowMask;//用来判断是否小于一个页大小 即小于8k
    final int numSmallSubpagePools;//small类型的子页数组的个数
    final int directMemoryCacheAlignment;//对齐的缓存尺寸比如32 64
    final int directMemoryCacheAlignmentMask;//对齐遮罩，求余数用，2的幂次可以用
    private final PoolSubpage<T>[] tinySubpagePools;//tiny类型子页数组 默认32个
    private final PoolSubpage<T>[] smallSubpagePools;//small类型子页数组 默认4个

	//根据块内存使用率状态分的数组，因为百分比是正数，所以就直接取整了
    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

	//块列表的一些指标度量
    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
	//通过原子操作记录大小
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();//Tiny的分配个数
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();//Small的分配个数
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();//huge的分配个数
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();//huge的字节大小

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;//8192
        this.maxOrder = maxOrder;// chunk 满二叉树高度 11
        this.pageShifts = pageShifts;// 用于辅助计算的 13  ===> 2 ^ 13 = 8192
        this.chunkSize = chunkSize;// 16M  chunk 大小
        directMemoryCacheAlignment = cacheAlignment;// 对齐基准
        directMemoryCacheAlignmentMask = cacheAlignment - 1;// 用于对齐内存
        //subpageOverflowMask 遮罩掩码，可以取出数的高位
		//举例：pageSize=8k，二进制就是10000000000000
		//pageSize - 1的二进制就是01111111111111，再取反就会发现低13位全是0，高位全是1，刚好可以用来提取高位
		//任何一个高位是1，值就是大于等于8k(申请内存不可能是负数，前面会有检查，最高位不会是1)，其实主要是判断是否小于8k，即一页大小，
		//然后来用Tiny或者Small尺寸来处理，比如isTinyOrSmall方法
        subpageOverflowMask = ~(pageSize - 1);//-8192
		//创建PoolSubpage数组，其实PoolSubpage是一个双向链表
		// subPage 双向链表  numTinySubpagePools = 32 为啥是32呢？ 上面提到是16为单位递增，那么就是 512/16 = 512 >>> 4 = 32
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);//创建tiny子页数组
        for (int i = 0; i < tinySubpagePools.length; i ++) {
        	//创建了一个只记录页大小的子页的头结点，头结点的前驱和后继都指向自己
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);//创建每个tiny子页链表的头结点
        }

		//剩余Small子页的个数 也就是[512-pageSize)范围内的尺寸类型的个数 pageSize=8192= 1<<<13 。512= 1<<<9 中间的尺寸类型个数是13-9=4
        numSmallSubpagePools = pageShifts - 9;//13-9=4  4种尺寸其实就是512 1k 2k 4k
		// subPage 双向链表 numSmallSubpagePools = 4 也可以理解为 512 << 4 = 8192（Small最大值）  所以是 4
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);//创建每个small子页链表的头结点
        }

		//双向链表
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);//没有前一个列表，可以直接删除块
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
		//获取 PooledByteBuf
    	PooledByteBuf<T> buf = newByteBuf(maxCapacity); // 初始化一块容量为 2^31 - 1的ByteBuf
    	//核心方法allocate，分配内存重点
        allocate(cache, buf, reqCapacity);// 进入分配逻辑
        return buf;
    }

    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
	//举例：pageSize=8k，二进制就是10000000000000
	//pageSize - 1的二进制就是01111111111111，再取反就会发现低13位全是0，高位全是1，刚好可以用来提取高位
	//任何一个高位是1，值就是大于等于8k(申请内存不可能是负数，前面会有检查，最高位不会是1)，其实主要是判断是否小于8k，即一页大小，
	// 然后来用Tiny或者Small尺寸来处理，比如isTinyOrSmall方法
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        final int normCapacity = normalizeCapacity(reqCapacity);//进行容量的规范化
		//isTinyOrSmall申请容量是否小于页大小8k
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize 小于pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
			//如果是小于8k的还要判断是Tiny还是Small类型，这个也是位运算，取了大于等于512的所有高位，看是否
			//是0，是的话就说明小于512，否则就大于等于512
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
				// 将分配区域转移到 tinySubpagePools 中
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;//从缓存中可以拿到就返回
                }

				// 如果无法从当前线程缓存中申请到内存，则尝试从tinySubpagePools中申请，这里tinyIdx()方法
				// 就是计算目标内存是在tinySubpagePools数组中的第几号元素中的

                //获取tiny的索引
				//tiny数组长度是32，是512>>>4，范围是16-496，间隔16，
				//所以容量>>>4可以获取对应索引，因为前面容量规范化过了，tiny最小是16，所以实际能获取对应的索引1-31，索引0给头结点
                tableIdx = tinyIdx(normCapacity);//获取tiny数组下标
                table = tinySubpagePools;//获取数组
            } else {
				// 如果目标内存在512byte~8KB之间，则尝试从smallSubpagePools中申请内存。这里首先从
				// 当前线程的缓存中申请small级别的内存，如果申请到了，则直接返回
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {//从缓存中获取
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

			// 获取目标元素的头结点
            final PoolSubpage<T> head = table[tableIdx];//获取头结点

            /**
			 * 因为一个区域可能有多个线程操作，所以链表操作需要同步
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
			// 这里需要注意的是，由于对head进行了加锁，而在同步代码块中判断了s != head，
			// 也就是说PoolSubpage链表中是存在未使用的PoolSubpage的，因为如果该节点已经用完了，
			// 其是会被移除当前链表的。也就是说只要s != head，那么这里的allocate()方法
			// 就一定能够申请到所需要的内存块
            synchronized (head) {
				// s != head就证明当前PoolSubpage链表中存在可用的PoolSubpage，并且一定能够申请到内存，
				// 因为已经耗尽的PoolSubpage是会从链表中移除的
                final PoolSubpage<T> s = head.next;
				// 如果此时 subpage 已经被分配过内存了执行下文，如果只是初始化过，则跳过该分支
                if (s != head) {//不是头结点就直接拿出来分配，头结点初始化的时候next和 prea指向自己
								// 如果双向链表已经初始化，那么从subpage中进行分配
					// 从PoolSubpage中申请内存
                    assert s.doNotDestroy && s.elemSize == normCapacity;// 断言确保 当前的这个subPage中的elemSize大小必须和校对后的请求大小一样
					// 通过申请的内存对ByteBuf进行初始化
                    long handle = s.allocate();// 从subPage中进行分配
                    assert handle >= 0;
					// 初始化 PoolByteBuf 说明其位置被分配到该区域，但此时尚未分配内存
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    incTinySmallAllocation(tiny);
                    return;
                }
            }
            //此时子页PoolSubpage只有头结点的情况，就会进行allocateNormal，负责申请内存
			//上边 s != head false
            synchronized (this) {//多线程共享的区域需要同步
				// 走到这里，说明目标PoolSubpage链表中无法申请到目标内存块，因而就尝试从PoolChunk中申请
                allocateNormal(buf, reqCapacity, normCapacity);// 双向循环链表还没初始化，使用normal分配
            }

            incTinySmallAllocation(tiny);//增加分配次数
            return;
        }
		// 走到这里说明目标内存是大于8KB的，那么就判断目标内存是否大于16M，如果大于16M，
		// 则不使用内存池对其进行管理，如果小于16M，则到PoolChunkList中进行内存申请
        if (normCapacity <= chunkSize) {
        	// 小于16M，首先到当前线程的缓存中申请，如果申请到了则直接返回，如果没有申请到，
			// 则到PoolChunkList中进行申请
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
			// 对于大于16M的内存，Netty不会对其进行维护，而是直接申请，然后返回给用户使用
            allocateHuge(buf, reqCapacity);//超过chunkSize的huge
        }
    }

    // Method must be called inside synchronized(this) { ... } block
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
    	//根据顺序q050，q025，q000，qInit，q075的顺序进行内存申请，之所以是这个顺序，我想可能是因为可以申请内存的使用率大吧，
		// 前3个使用率都有50%，而且都是相邻的，移动的时候也方便点，后两个是25%
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;//分配成功就返回
        }// 先尝试从chunk链表中进行分配， 这里有一个需要注意的地方
		// 分配顺序 先分配 q50 也就是 50% ~ 100% 的，然后是 q25 也就是 25% ~ 75% 的， 然后是 q000 0%~50% 、 qInit 0%~25% 、 q75 75% ~ 100%

        // Add a new chunk.不成功就增加一个块，涉及到树的操作
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);// 新建一个Chunk
        boolean success = c.allocate(buf, reqCapacity, normCapacity);// 内存分配
        assert success;
        qInit.add(c);//加入到初始块列表里 // 添加到初始化的chunk链表
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
		// 如果是非池化的，则直接销毁目标内存块，并且更新相关的数据
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
			// 如果是池化的，首先判断其是哪种类型的，即tiny，small或者normal，
			// 然后将其交由当前线程的缓存进行处理，如果添加成功，则直接返回
            SizeClass sizeClass = sizeClass(normCapacity);
            //进行了尺寸类型的获取，然后如果是有缓存的，就尝试添加到缓存，如果添加成功就返回，不然就要释放块内存
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it. 缓存成功就返回
                return;
            }

			// 如果当前线程的缓存已满，则将目标内存块返还给公共内存块进行处理
            freeChunk(chunk, handle, sizeClass, nioBuffer, false);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer, boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    case Tiny:
                        ++deallocationsTiny;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;//获取索引
            table = tinySubpagePools;//获取子页数组
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {//[512-pageSize) 尺寸和索引对应: 512->0 1024->1 2048->2 4096->3 刚是smallSubpagePoolsde的全部索引
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

	/**
	 * 规范化申请容量
	 * 请求的容量不一定是我们规定的尺寸，我们要根据不同的尺寸范围，对请求的容量进行规范化，
	 * 比如我们最小单位是16，如果你请求小于16，那就会被规范化为16。
	 * 然后是按照大到小的顺序进行容量类型的判定，返回规范化后的容量
	 *
	 * 如果申请容量大于等于chunkSize，再看是否要对齐，然后直接返回了。
	 * 如果大于等于512，就规范化到大于等于申请容量的规范类型，比如申请513，规范化到1k，申请1.5k，规范化到2k。
	 * 否则就是小于512的，如果小于16就补齐到16，否则就规范到16的倍数
	 */
	int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity");//检查非负

		//大于等于块大小就判断是否要对齐 ，返回处理后的大小
        if (reqCapacity >= chunkSize) {
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

		//不是Tiny类型的 向上取到大于等于申请容量的规范类型 512 1k 2k 4k 这4个类型
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled

            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

		//对齐处理
        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Quantum-spaced
		// Quantum-spaced Tiny类型又是16的倍数
        if ((reqCapacity & 15) == 0) {//位运算，取出余数，为0就表示是16的倍数
            return reqCapacity;
        }

		//非16倍数的 向上取成16的倍数 比如要100，(reqCapacity & ~15)先减去余数4，然后+16，即变成了112,16的7倍
        return (reqCapacity & ~15) + 16;
    }

    //directMemoryCacheAlignment 缓存对齐的大小 64
	//directMemoryCacheAlignmentMask = 63，遮罩掩码，可以取出数的低位。
	// directMemoryCacheAlignmentMask是用来获取对directMemoryCacheAlignment取余的余数，所以应该是63，二进制就是111111，跟任何数做&操作，都可以获取对64的取余的余数
    //举例：如果申请100，alignCapacity(100)之后就是128
	//1.delta = 100 & 63 = 100 - 64 = 36
	//2.delta == 0 ? 100 : 100 + 64 - 36
	//最后对齐之后就是128
    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;//取出余数
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;//加上对齐标准，减去余数，就可以对齐了
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // This does not touch buf's reader/writer indices
        allocate(parent.threadCache(), buf, newCapacity);
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
        	//需要一个新的PooledByteBuf，不管是不是unsafe，都是从一个 RECYCLER 的对象池里取得返回
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }
}
