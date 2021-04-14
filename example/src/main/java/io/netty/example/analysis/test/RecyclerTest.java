package io.netty.example.analysis.test;

import io.netty.util.Recycler;

/**
 * TODO
 *
 * @author chenyang.yue@ttpai.cn
 */
public class RecyclerTest {

	public static void main(String[] args) {
		User user = userRecycler.get();
		user.setName("test");
		System.out.println(user.getName());
		System.out.println(user);

		Thread thread = new Thread(() -> {
			User user1 = userRecycler.get();
			System.out.println();
		});


	}

	private static final Recycler<User> userRecycler = new Recycler<User>() {
		@Override
		protected User newObject(Handle<User> handle) {
			return new User(handle);
		}
	};

	static final class User {
		private String name;
		private Recycler.Handle<User> handle;

		public User(Recycler.Handle<User> handle) {
			this.handle = handle;
		}

		public void recycle() {
			handle.recycle(this);
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}
}
