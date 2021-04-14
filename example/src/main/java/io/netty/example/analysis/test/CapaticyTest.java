package io.netty.example.analysis.test;

public class CapaticyTest {

	public static void main(String[] args) {
		int index = 16;
		int newCapacity = index;// index是这个FastThreadLocal对应的索引值
		newCapacity |= newCapacity >>>  1;
		newCapacity |= newCapacity >>>  2;
		newCapacity |= newCapacity >>>  4;
		newCapacity |= newCapacity >>>  8;
		newCapacity |= newCapacity >>> 16;
		newCapacity ++;
		System.out.println(newCapacity);
	}
}
