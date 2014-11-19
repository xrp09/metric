package com.xqbase.metric.collector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.InflaterInputStream;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.xqbase.metric.common.Metric;
import com.xqbase.metric.common.MetricEntry;
import com.xqbase.util.ByteArrayQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Runnables;
import com.xqbase.util.ShutdownHook;
import com.xqbase.util.Time;

public class Collector {
	private static final int MAX_BUFFER_SIZE = 64000;

	private static String decode(String s) {
		try {
			return URLDecoder.decode(s, "UTF-8");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static BasicDBObject row(Map<String, String> tagMap,
			int now, int count, double sum, double max, double min, double sqr) {
		BasicDBObject row = new BasicDBObject(tagMap);
		row.put("_minute", Integer.valueOf(now));
		row.put("_count", Integer.valueOf(count));
		row.put("_sum", Double.valueOf(sum));
		row.put("_max", Double.valueOf(max));
		row.put("_min", Double.valueOf(min));
		row.put("_sqr", Double.valueOf(sqr));
		return row;
	}

	private static void put(HashMap<String, ArrayList<DBObject>> rowsMap,
			String name, DBObject row) {
		ArrayList<DBObject> rows = rowsMap.get(name);
		if (rows == null) {
			rows = new ArrayList<>();
			rowsMap.put(name, rows);
		}
		rows.add(row);
	}

	private static void insert(DB db, HashMap<String, ArrayList<DBObject>> rowsMap) {
		for (Map.Entry<String, ArrayList<DBObject>> entry :
				rowsMap.entrySet()) {
			String name = entry.getKey();
			ArrayList<DBObject> rows = entry.getValue();
			db.getCollection(name).insert(rows);
			if (verbose) {
				Log.d("Inserted " + rows.size() + " rows into metric \"" + name + "\".");
			}
		}
	}

	private static final BasicDBObject INDEX_KEY = new BasicDBObject("_minute", Integer.valueOf(1));

	private static ShutdownHook hook = new ShutdownHook();
	private static boolean verbose;

	public static void main(String[] args) {
		if (hook.isShutdown(args)) {
			return;
		}
		Logger logger = Log.getAndSet(Conf.openLogger("Collector.", 16777216, 10));
		ExecutorService executor = Executors.newCachedThreadPool();
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
		Log.i("Metric Collector Started");

		Properties p = Conf.load("Collector");
		String host = p.getProperty("host");
		int port = Numbers.parseInt(p.getProperty("port"), 5514);
		int serverId = Numbers.parseInt(p.getProperty("server_id"), 0);
		int expire = Numbers.parseInt(p.getProperty("expire"), 43200);
		boolean enableRemoteAddr = Conf.getBoolean(p.getProperty("remote_addr"), true);
		String allowedRemote = p.getProperty("allowed_remote");
		HashSet<String> allowedRemotes = null;
		if (allowedRemote != null) {
			allowedRemotes = new HashSet<>(Arrays.asList(allowedRemote.split("[,;]")));
		}
		verbose = Conf.getBoolean(p.getProperty("verbose"), false);
		long start = System.currentTimeMillis();
		AtomicInteger now = new AtomicInteger((int) (start / Time.MINUTE));
		p = Conf.load("Mongo");
		MongoClient mongo = null;
		Runnable schedule = null;
		try (DatagramSocket socket = new DatagramSocket(new
				InetSocketAddress(host == null ? "0.0.0.0" : host, port))) {
			mongo = new MongoClient(p.getProperty("host"),
					Numbers.parseInt(p.getProperty("port"), 27017));
			DB db = mongo.getDB(p.getProperty("db"));
			// Schedule Statistics and Index
			schedule = () -> {
				int now_ = now.getAndIncrement();
				// Insert aggregation-during-collection metrics
				HashMap<String, ArrayList<DBObject>> rowsMap = new HashMap<>();
				for (MetricEntry entry : Metric.removeAll()) {
					BasicDBObject row = row(entry.getTagMap(), now_, entry.getCount(),
							entry.getSum(), entry.getMax(), entry.getMin(), entry.getSqr());
					put(rowsMap, entry.getName(), row);
				}
				if (!rowsMap.isEmpty()) {
					insert(db, rowsMap);
				}
				// Ensure index and calculate metric size by master collector
				if (serverId == 0) {
					ArrayList<DBObject> rows = new ArrayList<>();
					for (String name : db.getCollectionNames()) {
						if (name.startsWith("system.")) {
							continue;
						}
						DBCollection collection = db.getCollection(name);
						collection.createIndex(INDEX_KEY);
						int count = (int) collection.count();
						rows.add(row(Collections.singletonMap("name", name),
								now_, 1, count, count, count, count * count));
					}
					if (!rows.isEmpty()) {
						db.getCollection("metric.size").insert(rows);
					}
				}
			};
			timer.scheduleAtFixedRate(Runnables.wrap(schedule),
					Time.MINUTE - start % Time.MINUTE, Time.MINUTE, TimeUnit.MILLISECONDS);
			// Schedule removal of stale metrics by master collector
			if (serverId == 0) {
				timer.scheduleAtFixedRate(Runnables.wrap(() -> {
					Integer notBefore = Integer.valueOf(now.get() - expire);
					Integer notAfter = Integer.valueOf(now.get() + expire);
					for (String name : db.getCollectionNames()) {
						if (name.startsWith("system.")) {
							continue;
						}
						DBCollection collection = db.getCollection(name);
						collection.remove(new BasicDBObject("_minute",
								new BasicDBObject("$lt", notBefore)));
						collection.remove(new BasicDBObject("_minute",
								new BasicDBObject("$gt", notAfter)));
					}
				}), Time.HOUR - start % Time.HOUR + Time.MINUTE / 2,
						Time.HOUR, TimeUnit.MILLISECONDS);
			}

			hook.register(socket);
			while (!Thread.interrupted()) {
				// Receive
				byte[] buf = new byte[65536];
				DatagramPacket packet = new DatagramPacket(buf, buf.length);
				// Blocked, or closed by shutdown handler
				socket.receive(packet);
				int len = packet.getLength();
				String remoteAddr = packet.getAddress().getHostAddress();
				if (allowedRemotes != null && !allowedRemotes.contains(remoteAddr)) {
					Log.w(remoteAddr + " not allowed");
					continue;
				}
				if (enableRemoteAddr) {
					Metric.put("metric.throughput", len,
							"remote_addr", remoteAddr, "server_id", "" + serverId);
				} else {
					Metric.put("metric.throughput", len, "server_id", "" + serverId);
				}
				// Inflate
				ByteArrayQueue baq = new ByteArrayQueue();
				byte[] buf_ = new byte[2048];
				try (InflaterInputStream inflater = new InflaterInputStream(new
						ByteArrayInputStream(buf, 0, len))) {
					int bytesRead;
					while ((bytesRead = inflater.read(buf_)) > 0) {
						baq.add(buf_, 0, bytesRead);
						// Prevent attack
						if (baq.length() > MAX_BUFFER_SIZE) {
							break;
						}
					}
				} catch (IOException e) {
					Log.w("Unable to inflate packet from " + remoteAddr);
					// Continue to parse rows
				}

				HashMap<String, ArrayList<DBObject>> rowsMap = new HashMap<>();
				for (String line : baq.toString().split("\n")) {
					// Truncate tailing '\r'
					int length = line.length();
					if (length > 0 && line.charAt(length - 1) == '\r') {
						line = line.substring(0, length - 1);
					}
					// Parse name, aggregation, value and tags
					// <name>/<aggregation>/<value>[?<tag>=<value>[&...]]
					String[] paths;
					HashMap<String, String> tagMap = new HashMap<>();
					int index = line.indexOf('?');
					if (index < 0) {
						paths = line.split("/");
					} else {
						paths = line.substring(0, index).split("/");
						String tags = line.substring(index + 1);
						for (String tag : tags.split("&")) {
							index = tag.indexOf('=');
							if (index > 0) {
								tagMap.put(decode(tag.substring(0, index)),
										decode(tag.substring(index + 1)));
							}
						}
					}
					if (paths.length < 2) {
						Log.w("Incorrect format: [" + line + "]");
						continue;
					}
					String name = decode(paths[0]);
					if (name.isEmpty()) {
						Log.w("Incorrect format: [" + line + "]");
						continue;
					}
					if (enableRemoteAddr) {
						tagMap.put("remote_addr", remoteAddr);
						Metric.put("metric.rows", 1, "name", name,
								"remote_addr", remoteAddr, "server_id", "" + serverId);
					} else {
						Metric.put("metric.rows", 1, "name", name,
								"server_id", "" + serverId);
					}
					if (paths.length < 6) {
						// For aggregation-during-collection metric, aggregate first
						Metric.put(name, Numbers.parseDouble(paths[1]), tagMap);
					} else {
						// For aggregation-before-collection metric, insert immediately
						int count = Numbers.parseInt(paths[2]);
						double sum = Numbers.parseDouble(paths[3]);
						put(rowsMap, name, row(tagMap, Numbers.parseInt(paths[1], now.get()),
								count, sum, Numbers.parseDouble(paths[4]), Numbers.parseDouble(paths[5]),
								paths.length == 6 ? sum * sum / count : Numbers.parseDouble(paths[6])));
					}
				}
				// Insert aggregation-before-collection metrics
				if (!rowsMap.isEmpty()) {
					executor.execute(Runnables.wrap(() -> insert(db, rowsMap)));
				}
			}
		} catch (IOException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		} finally {
			// Do not do Mongo operations in main thread (may be interrupted)
			if (schedule != null) {
				executor.execute(Runnables.wrap(schedule));
			}
			Runnables.shutdown(executor);
			Runnables.shutdown(timer);
			if (mongo != null) {
				mongo.close();
			}
		}
		Log.i("Metric Collector Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
	}
}