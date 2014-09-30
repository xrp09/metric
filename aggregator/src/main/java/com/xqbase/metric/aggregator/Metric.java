package com.xqbase.metric.aggregator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;

public class Metric {
	private static final int MINUTE = 60000;
	private static final int MAX_PACKET_SIZE = 64000;

	private static ConcurrentHashMap<MetricKey, MetricValue>
			map = new ConcurrentHashMap<>();

	private static void put(MetricKey key, double value) {
		MetricValue current;
		do {
			current = map.get(key);
		} while (current == null ? map.putIfAbsent(key, new MetricValue(value)) != null :
				!map.replace(key, current, new MetricValue(current, value)));
	}

	public static void put(String name, double value, HashMap<String, String> tagMap) {
		put(new MetricKey(name, tagMap), value);
	}

	public static void put(String name, double value, String... tagPairs) {
		put(new MetricKey(name, tagPairs), value);
	}

	public static ArrayList<MetricEntry> removeAll() {
		ArrayList<MetricEntry> metrics = new ArrayList<>();
		ArrayList<MetricKey> keys = new ArrayList<>(map.keySet());
		for (MetricKey key : keys) {
			MetricValue value = map.remove(key);
			if (value != null) {
				metrics.add(new MetricEntry(key, value));
			}
		}
		return metrics;
	}

	private static String encode(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void send(DatagramSocket socket,
			InetSocketAddress addr, String data) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (DeflaterOutputStream dos = new
				DeflaterOutputStream(baos)) {
			dos.write(data.getBytes());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		// Resolve "addr" every time
		DatagramPacket packet = new DatagramPacket(baos.toByteArray(), baos.size(),
				new InetSocketAddress(addr.getHostString(), addr.getPort()));
		socket.send(packet);
	}

	static void send(InetSocketAddress[] addrs, int minute) {
		ArrayList<MetricEntry> metrics = removeAll();
		if (metrics.isEmpty()) {
			return;
		}
		StringBuilder packet = new StringBuilder();
		try (DatagramSocket socket = new DatagramSocket()) {
			for (MetricEntry metric : metrics) {
				StringBuilder row = new StringBuilder();
				row.append(encode(metric.getName())).append('/').
						append(minute).append('/').
						append(metric.getCount()).append('/').
						append(metric.getSum()).append('/').
						append(metric.getMax()).append('/').
						append(metric.getMin());
				HashMap<String, String> tagMap = metric.getTagMap();
				if (!tagMap.isEmpty()) {
					int question = row.length();
					for (Map.Entry<String, String> tag : tagMap.entrySet()) {
						row.append('&').append(encode(tag.getKey())).
								append('=').append(encode(tag.getValue()));
					}
					row.setCharAt(question, '?');
				}
				if (packet.length() + row.length() >= MAX_PACKET_SIZE) {
					for (InetSocketAddress addr : addrs) {
						send(socket, addr, packet.toString());
					}
					packet.setLength(0);
				}
				packet.append(row).append('\n');
			}
			for (InetSocketAddress addr : addrs) {
				send(socket, addr, packet.toString());
			}
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}

	private static ScheduledThreadPoolExecutor timer = null;
	private static Runnable command;

	public static synchronized void startup(final InetSocketAddress... addrs) {
		if (timer != null) {
			return;
		}
		long start = System.currentTimeMillis();
		final AtomicInteger now = new AtomicInteger((int) (start / MINUTE));
		command = new Runnable() {
			@Override
			public void run() {
				try {
					send(addrs, now.getAndIncrement());
				} catch (Error | RuntimeException e) {
					e.printStackTrace();
				}
			}
		};
		timer = new ScheduledThreadPoolExecutor(1);
		timer.scheduleAtFixedRate(command,
				MINUTE - start % MINUTE, MINUTE, TimeUnit.MILLISECONDS);
	}

	public static synchronized void shutdown() {
		if (timer == null) {
			return;
		}
		timer.shutdown();
		timer = null;
		command.run();
	}
}