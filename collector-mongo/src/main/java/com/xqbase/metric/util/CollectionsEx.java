package com.xqbase.metric.util;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BiConsumer;

public class CollectionsEx {
	public static <T> PriorityQueue<T> max(Collection<? extends T> collection,
			Comparator<? super T> comparator, int limit) {
		PriorityQueue<T> queue = new PriorityQueue<>(comparator);
		for (T t : collection) {
			queue.offer(t);
			if (queue.size() > limit) {
				queue.poll();
			}
		}
		return queue;
	}

	public static <T> PriorityQueue<T> min(Collection<? extends T> collection,
			Comparator<? super T> comparator, int limit) {
		return max(collection, comparator.reversed(), limit);
	}

	public static <K, V> void
			forEach(Collection<Map.Entry<? extends K, ? extends V>> entries,
			BiConsumer<? super K, ? super V> action) {
		entries.forEach(entry -> action.accept(entry.getKey(), entry.getValue()));
	}

	public static <K, V> Map<K, V>
			toMap(Collection<Map.Entry<? extends K, ? extends V>> entries) {
		Map<K, V> map = new HashMap<>();
		forEach(entries, map::put);
		return map;
	}
}