package com.xqbase.metric.util;

import java.nio.charset.StandardCharsets;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.xqbase.util.ByteArrayQueue;
import com.xqbase.util.Log;
import com.xqbase.util.Pool;

public class Kryos {
	private static Pool<Kryo, RuntimeException> kryoPool =
			new Pool<Kryo, RuntimeException>(Kryo::new, kryo -> {/**/}, 0) {
		@Override
		public Pool<Kryo,RuntimeException>.Entry borrow() {
			Pool<Kryo,RuntimeException>.Entry entry = super.borrow();
			entry.getObject().reset();
			return entry;
		}
	};

	public static String serialize(Object o) {
		ByteArrayQueue baq = new ByteArrayQueue();
		try (
			Pool<Kryo, RuntimeException>.Entry entry = kryoPool.borrow();
			Output output = new Output(baq.getOutputStream());
		) {
			entry.getObject().writeObject(output, o);
			entry.setValid(true);
		}
		return baq.toString(StandardCharsets.ISO_8859_1);
	}

	/** Be sure to do null pointer check on return value !!! */
	public static <T> T deserialize(String s, Class<T> clazz) {
		try (
			Pool<Kryo, RuntimeException>.Entry entry = kryoPool.borrow();
			Input input = new Input(s.getBytes(StandardCharsets.ISO_8859_1));
		) {
			T t = entry.getObject().readObject(input, clazz);
			entry.setValid(true);
			return t;
		} catch (KryoException e) {
			Log.w(e.getMessage());
			return null;
		}
	}
}