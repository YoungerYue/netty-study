package io.netty.example.analysis.test;

import io.netty.util.internal.InternalThreadLocalMap;

/**
 * TODO
 *
 * @author chenyang.yue@ttpai.cn
 */
public class InternalThreadLocalMapTest {

	public static void main(String[] args) {
		InternalThreadLocalMap internalThreadLocalMap = InternalThreadLocalMap.get();
		Object object = new Object();
		System.out.println(object);
		internalThreadLocalMap.setIndexedVariable(2, object);
		internalFunction();
	}

	private static void internalFunction(){
		InternalThreadLocalMap internalThreadLocalMap = InternalThreadLocalMap.get();
		Object o1 = internalThreadLocalMap.indexedVariable(33);
		System.out.println(o1);

		Object o2 = internalThreadLocalMap.indexedVariable(34);
		System.out.println(o2);
	}
}
