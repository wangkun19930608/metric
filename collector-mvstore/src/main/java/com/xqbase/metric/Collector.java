package com.xqbase.metric;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.zip.InflaterInputStream;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import com.xqbase.metric.client.ManagementMonitor;
import com.xqbase.metric.common.Metric;
import com.xqbase.metric.common.MetricEntry;
import com.xqbase.metric.common.MetricValue;
import com.xqbase.metric.util.Codecs;
import com.xqbase.metric.util.CollectionsEx;
import com.xqbase.util.ByteArrayQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Runnables;
import com.xqbase.util.Service;
import com.xqbase.util.Strings;
import com.xqbase.util.Time;
import com.xqbase.util.concurrent.CountLock;
import com.xqbase.util.concurrent.LockMap;

class NameTime {
	String name;
	int time;
	
	@Override
	public int hashCode() {
		return name.hashCode() * 31 + time;
	}

	@Override
	public boolean equals(Object obj) {
		NameTime o = (NameTime) obj;
		return time == o.time && name.equals(o.name);
	}
}

public class Collector {
	private static final int MAX_BUFFER_SIZE = 1048576;
	private static final int MAX_METRIC_LEN = 64;

	private static final Map<Map<String, String>, MetricValue>
			METRIC_TYPE = new HashMap<>();
	private static final Map<String, Map<String, MetricValue>>
			TAGS_TYPE = new HashMap<>();

	private static double __(String s) {
		double d = Numbers.parseDouble(s);
		return Double.isNaN(d) ? 0 : d;
	}

	private static String decode(String s, int limit) {
		String result = Strings.decodeUrl(s);
		return limit > 0 ? Strings.truncate(result, limit) : result;
	}

	private static void put(Map<NameTime, Map<Map<String, String>, MetricValue>>
			metricMaps, String name, int time, Map<String, String> tags,
			long count, double sum, double max, double min, double sqr) {
		Map<String, String> limitedTags;
		if (maxTags > 0 && tags.size() > maxTags) {
			limitedTags = new HashMap<>();
			CollectionsEx.forEach(CollectionsEx.min(tags.entrySet(),
					Comparator.comparing(Map.Entry::getKey), maxTags), limitedTags::put);
		} else {
			limitedTags = tags;
		}
		NameTime filename = new NameTime();
		filename.name = name;
		filename.time = time;
		merge(metricMaps.computeIfAbsent(filename, k -> new HashMap<>()),
				limitedTags, new MetricValue(count, sum, max, min, sqr));
	}

	private static void insert(Map<NameTime, Map<Map<String, String>, MetricValue>> metricMaps) {
		metricMaps.forEach((key, metricMap) -> {
			if (metricMap.isEmpty()) {
				return;
			}
			long t = System.currentTimeMillis();
			CountLock lock = lockMap.acquire(key);
			lock.lock();
			try {
				Map<Integer, byte[]> metricTable = mv.openMap(key.name);
				Integer time = Integer.valueOf(key.time);
				byte[] b = metricTable.get(time);
				Map<Map<String, String>, MetricValue> aggrMetricMap;
				if (b == null) {
					aggrMetricMap = metricMap;
				} else {
					aggrMetricMap = Codecs.deserialize(b, METRIC_TYPE);
					putMetricValue(aggrMetricMap, metricMap);
				}
				metricTable.put(time, Codecs.serialize(aggrMetricMap));
			} finally {
				lock.unlock();
				lockMap.release(key, lock);
				Metric.put("metric.mvstore.elapsed", System.currentTimeMillis() - t,
						"command", "insert", "name", key.name);
			}
		});
	}

	private static Service service = new Service();
	private static LockMap<NameTime> lockMap = new LockMap<>();
	private static MVStore mv;
	private static int expire, tagsExpire, maxTags, maxTagValues,
			maxTagCombinations, maxTagNameLen, maxTagValueLen;
	private static boolean verbose;
	private static volatile Map<String, long[]> namesCache = Collections.emptyMap();

	private static long getSize(String name) {
		long t = System.currentTimeMillis();
		MVMap<?, ?> table = mv.openMap(name);
		long size = table.isEmpty() ? 0 : table.getRootPage().getDiskSpaceUsed();
		Metric.put("metric.mvstore.elapsed", System.currentTimeMillis() - t,
				"command", "size", "name", name);
		return size;
	}

	private static Map<String, long[]> getNames() {
		Map<String, long[]> names = new HashMap<>();
		for (String mapName : mv.getMapNames()) {
			if (mapName.startsWith("_tags_quarter.")) {
				continue;
			}
			if (mapName.startsWith("_quarter.")) {
				String name = mapName.substring(9);
				names.computeIfAbsent(name,
						k -> new long[] {0, 0, 0})[1] = getSize(mapName);
				continue;
			}
			if (mapName.equals("_meta.aggregated")) {
				mv.<String, Integer>openMap("_meta.aggregated").forEach((name, time) -> {
					long[] value = names.get(name);
					if (value != null) {
						value[2] = time.intValue();
					}
				});
				continue;
			}
			if (mapName.equals("_meta.tags")) {
				continue;
			}
			names.computeIfAbsent(mapName,
					k -> new long[] {0, 0, 0})[0] = getSize(mapName);
		}
		namesCache = names;
		return names;
	}

	private static void minutely(int minute) {
		// Insert aggregation-during-collection metrics
		Map<NameTime, Map<Map<String, String>, MetricValue>> metricMaps = new HashMap<>();
		for (MetricEntry entry : Metric.removeAll()) {
			put(metricMaps, entry.getName(), minute, entry.getTagMap(), entry.getCount(),
					entry.getSum(), entry.getMax(), entry.getMin(), entry.getSqr());
		}
		insert(metricMaps);
		// Put metric size
		namesCache.forEach((name, size) -> {
			Metric.put("metric.size", size[0], "name", name);
			Metric.put("metric.size", size[1], "name", "_quarter." + name);
		});
	}

	private static void merge(Map<Map<String, String>, MetricValue>
			metricMap, Map<String, String> key, MetricValue newValue) {
		MetricValue value = metricMap.get(key);
		if (value == null) {
			metricMap.put(key, newValue);
		} else {
			value.add(newValue);
		}
	}

	private static void putMetricValue(Map<Map<String, String>, MetricValue>
			aggrMetricMap, Map<Map<String, String>, MetricValue> metricMap) {
		metricMap.forEach((tags, value) -> merge(aggrMetricMap, tags, value));
	}

	private static void putTagValue(Map<String, Map<String, MetricValue>> tagMap,
			String tagKey, String tagValue, MetricValue value) {
		Map<String, MetricValue> tagValues = tagMap.get(tagKey);
		if (tagValues == null) {
			tagValues = new HashMap<>();
			tagMap.put(tagKey, tagValues);
			// Must use "value.clone()" here, because many tags may share one "value" 
			tagValues.put(tagValue, value.clone());
		} else {
			MetricValue oldValue = tagValues.get(tagValue);
			if (oldValue == null) {
				// Must use "value.clone()" here
				tagValues.put(tagValue, value.clone());
			} else {
				oldValue.add(value);
			}
		}
	}

	private static Map<String, Map<String, MetricValue>>
			limit(Map<String, Map<String, MetricValue>> tagMap) {
		Map<String, Map<String, MetricValue>> tags = new HashMap<>();
		BiConsumer<String, Map<String, MetricValue>> action = (tagName, valueMap) -> {
			Map<String, MetricValue> tagValues = new HashMap<>();
			if (maxTagValues > 0 && valueMap.size() > maxTagValues) {
				CollectionsEx.forEach(CollectionsEx.max(valueMap.entrySet(),
						Comparator.comparingLong(metricValue ->
						metricValue.getValue().getCount()), maxTagValues), tagValues::put);
			} else {
				tagValues.putAll(valueMap);
			}
			tags.put(tagName, tagValues);
		};
		if (maxTags > 0 && tagMap.size() > maxTags) {
			CollectionsEx.forEach(CollectionsEx.min(tagMap.entrySet(),
					Comparator.comparing(Map.Entry::getKey), maxTags), action);
		} else {
			tagMap.forEach(action);
		}
		return tags;
	}

	private static void delete(MVMap<Integer, ?> table, int time) {
		long t = System.currentTimeMillis();
		List<Integer> delKeys = new ArrayList<>();
        Iterator<Integer> it = table.keyIterator(Integer.valueOf(0));
        while (it.hasNext()) {
        	Integer key = it.next();
        	if (key.intValue() > time) {
        		break;
        	}
        	delKeys.add(key);
        }
        for (Integer key : delKeys) {
        	table.remove(key);
        }
		Metric.put("metric.mvstore.elapsed", System.currentTimeMillis() - t,
				"command", "delete", "name", table.getName());
	}

	private static void quarterly(int quarter) {
		Map<String, Integer> aggregatedTable = mv.openMap("_meta.aggregated");
		Map<String, byte[]> tagsTable = mv.openMap("_meta.tags");
		getNames().forEach((name, sizeAndAggregated) -> {
			// 1. Delete _tags_quarter.*
			MVMap<Integer, byte[]> tagsQuarter = mv.openMap("_tags_quarter." + name);
			delete(tagsQuarter, quarter - tagsExpire);
			// 2. Delete minute and quarter data
			long minuteSize = sizeAndAggregated[0];
			long quarterSize = sizeAndAggregated[1];
			if (minuteSize == 0 && quarterSize == 0) {
				// 2.1 Delete folder if metric data does not exist
				mv.removeMap(name);
				mv.removeMap("_quarter." + name);
				mv.removeMap("_tags_quarter." + name);
				aggregatedTable.remove(name);
				tagsTable.remove(name);
				return;
			}
			int aggregated = (int) sizeAndAggregated[2];
			MVMap<Integer, byte[]> minuteTable = mv.openMap(name);
			MVMap<Integer, byte[]> quarterTable = mv.openMap("_quarter." + name);
			delete(minuteTable, quarter * 15 - expire);
			delete(quarterTable, quarter - expire);
			// 3. Aggregate minute to quarter
			long t = System.currentTimeMillis();
			int start = aggregated == 0 ? quarter - expire : aggregated;
			for (int i = start + 1; i <= quarter; i ++) {
				Map<Map<String, String>, MetricValue> accMetricMap = new HashMap<>();
				int i15 = i * 15;
				for (int j = i * 15 - 14; j <= i15; j ++) {
					byte[] b = minuteTable.get(Integer.valueOf(j));
					if (b != null) {
						putMetricValue(accMetricMap, Codecs.deserialize(b, METRIC_TYPE));
					}
				}
				// 3'. Aggregate to "_quarter.*"
				if (accMetricMap.isEmpty()) {
					continue;
				}
				int combinations = accMetricMap.size();
				Metric.put("metric.tags.combinations", combinations, "name", name);
				// 5. Aggregate to "_tags_quarter.*"
				Map<String, Map<String, MetricValue>> tagMap = new HashMap<>();
				BiConsumer<Map<String, String>, MetricValue> action = (tags, value) -> {
					// 5. Aggregate to "_tags_quarter.*"
					tags.forEach((tagKey, tagValue) -> {
						putTagValue(tagMap, tagKey, tagValue, value);
					});
				};
				if (maxTagCombinations > 0 && combinations > maxTagCombinations) {
					CollectionsEx.forEach(CollectionsEx.max(accMetricMap.entrySet(),
							Comparator.comparingLong(entry -> entry.getValue().getCount()),
							maxTagCombinations), action);
				} else {
					accMetricMap.forEach(action);
				}
				// 3'. Aggregate to "_quarter.*"
				quarterTable.put(Integer.valueOf(i), Codecs.serialize(accMetricMap));
				// 5. Aggregate to "_tags_quarter.*"
				tagMap.forEach((tagKey, tagValue) -> {
					Metric.put("metric.tags.values", tagValue.size(),
							"name", name, "key", tagKey);
				});
				// {"_quarter": i}, but not {"_quarter": quarter} !
				tagsQuarter.put(Integer.valueOf(i), Codecs.serialize(limit(tagMap)));
			}
			// 6. Set "aggregated"
			aggregatedTable.put(name, Integer.valueOf(quarter));
			// 7. Aggregate "_tags_quarter" to "_meta.tags";
			Map<String, Map<String, MetricValue>> tagMap = new HashMap<>();
			for (byte[] b : tagsQuarter.values()) {
				Codecs.deserialize(b, TAGS_TYPE).forEach((tagKey, tags) -> {
					tags.forEach((tagValue, value) -> {
						putTagValue(tagMap, tagKey, tagValue, value);
					});
				});
			}
			tagsTable.put(name, Codecs.serialize(limit(tagMap)));
			Metric.put("metric.mvstore.elapsed", System.currentTimeMillis() - t,
					"command", "aggregate", "name", name);
		});
	}

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("Collector.", 16777216, 10));
		ExecutorService executor = Executors.newCachedThreadPool();
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2);

		Properties p = Conf.load("Collector");
		int port = Numbers.parseInt(p.getProperty("port"), 5514);
		String host = p.getProperty("host");
		host = host == null || host.isEmpty() ? "0.0.0.0" : host;
		expire = Numbers.parseInt(p.getProperty("expire"), 2880);
		tagsExpire = Numbers.parseInt(p.getProperty("tags_expire"), 96);
		maxTags = Numbers.parseInt(p.getProperty("max_tags"));
		maxTagValues = Numbers.parseInt(p.getProperty("max_tag_values"));
		maxTagCombinations = Numbers.parseInt(p.getProperty("max_tag_combinations"));
		maxTagNameLen = Numbers.parseInt(p.getProperty("max_tag_name_len"));
		maxTagValueLen = Numbers.parseInt(p.getProperty("max_tag_value_len"));
		int quarterDelay = Numbers.parseInt(p.getProperty("quarter_delay"), 2);
		boolean enableRemoteAddr = Conf.getBoolean(p.getProperty("remote_addr"), true);
		String allowedRemote = p.getProperty("allowed_remote");
		Set<String> allowedRemotes = null;
		if (allowedRemote != null) {
			allowedRemotes = new HashSet<>(Arrays.asList(allowedRemote.split("[,;]")));
		}
		verbose = Conf.getBoolean(p.getProperty("verbose"), false);

		long start = System.currentTimeMillis();
		AtomicInteger currentMinute = new AtomicInteger((int) (start / Time.MINUTE));
		Runnable minutely = null;
		try (
			DatagramSocket socket = new DatagramSocket(new
					InetSocketAddress(host, port));
			ManagementMonitor monitor = new ManagementMonitor("metric.server");
		) {
			String dataDir = Conf.getAbsolutePath("data");
			new File(dataDir).mkdirs();
			mv = MVStore.open(dataDir + "/metric.mv");
			getNames();
			Dashboard.startup(mv);

			minutely = Runnables.wrap(() -> {
				int minute = currentMinute.incrementAndGet();
				minutely(minute);
				if (!service.isInterrupted() && minute % 15 == quarterDelay) {
					// Skip "quarterly" when shutdown
					quarterly(minute / 15);
				}
			});
			timer.scheduleAtFixedRate(minutely, Time.MINUTE - start % Time.MINUTE,
					Time.MINUTE, TimeUnit.MILLISECONDS);
			timer.scheduleAtFixedRate(monitor, 5, 5, TimeUnit.SECONDS);
			service.register(socket);

			Log.i("Metric Collector Started on UDP " + host + ":" + port);
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

				Map<NameTime, Map<Map<String, String>, MetricValue>>
						metricMap = new HashMap<>();
				Map<String, Integer> countMap = new HashMap<>();
				for (String line : baq.toString().split("\n")) {
					// Truncate tailing '\r'
					int length = line.length();
					if (length > 0 && line.charAt(length - 1) == '\r') {
						line = line.substring(0, length - 1);
					}
					// Parse name, aggregation, value and tags
					// <name>/<aggregation>/<value>[?<tag>=<value>[&...]]
					String[] paths;
					Map<String, String> tagMap = new HashMap<>();
					int index = line.indexOf('?');
					if (index < 0) {
						paths = line.split("/");
					} else {
						paths = line.substring(0, index).split("/");
						String query = line.substring(index + 1);
						for (String tag : query.split("&")) {
							index = tag.indexOf('=');
							if (index > 0) {
								tagMap.put(decode(tag.substring(0, index), maxTagNameLen),
										decode(tag.substring(index + 1), maxTagValueLen));
							}
						}
					}
					if (paths.length < 2) {
						Log.w("Incorrect format: [" + line + "]");
						continue;
					}
					String name = decode(paths[0], MAX_METRIC_LEN);
					if (name.isEmpty()) {
						Log.w("Incorrect format: [" + line + "]");
						continue;
					}
					if (enableRemoteAddr) {
						tagMap.put("remote_addr", remoteAddr);
					}
					if (paths.length > 6) {
						// For aggregation-before-collection metric, insert immediately
						put(metricMap, name, Numbers.parseInt(paths[1], currentMinute.get()),
								tagMap, Numbers.parseLong(paths[2]), __(paths[3]),
								__(paths[4]), __(paths[5]), __(paths[6]));
					} else {
						// For aggregation-during-collection metric, aggregate first
						Metric.put(name, __(paths[1]), tagMap);
					}
					Integer count = countMap.get(name);
					countMap.put(name, Integer.valueOf(count == null ?
							1 : count.intValue() + 1));
				}
				if (verbose) {
					Log.d("Metrics received from " + remoteAddr + ": " + countMap);
				}
				if (enableRemoteAddr) {
					Metric.put("metric.throughput", len, "remote_addr", remoteAddr);
					countMap.forEach((name, value) -> {
						Metric.put("metric.rows", value.intValue(), "name", name,
								"remote_addr", remoteAddr);
					});
				} else {
					Metric.put("metric.throughput", len);
					countMap.forEach((name, value) -> {
						Metric.put("metric.rows", value.intValue(), "name", name);
					});
				}
				// Insert aggregation-before-collection metrics
				if (!metricMap.isEmpty()) {
					executor.execute(Runnables.wrap(() -> insert(metricMap)));
				}
			}
		} catch (IOException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}
		Runnables.shutdown(timer);
		// Do not do file operations in main thread (may be interrupted)
		if (minutely != null) {
			executor.execute(minutely);
		}
		Runnables.shutdown(executor);
		Dashboard.shutdown();

		Log.i("Metric Collector Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}