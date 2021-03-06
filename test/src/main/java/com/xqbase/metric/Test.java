package com.xqbase.metric;

import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.xqbase.metric.client.ManagementMonitor;
import com.xqbase.metric.client.MetricClient;
import com.xqbase.metric.common.Metric;

public class Test {
	private static String v(double d) {
		return "" + (int) Math.floor(d * 10);
	}

	public static void main(String[] args) {
		MetricClient.setMaxPacketSize(MetricClient.MAX_PACKET_SIZE_FRAG);
		MetricClient.startup(new InetSocketAddress("127.0.0.1", 5514));
		String name = args.length > 0 ? args[0] : "test";
		ManagementMonitor monitor = new ManagementMonitor("metric-" + name);

		Random r = new Random();
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2);
		timer.scheduleAtFixedRate(() -> {
			for (int i = 0; i < 10_000; i ++) {
				double d = r.nextDouble();
				Metric.put(name, r.nextDouble(),
						"p", v(r.nextDouble()),
						"q", v(r.nextDouble() * r.nextDouble()),
						"r", v(d * d),
						"s", v(r.doubles(10).average().getAsDouble()));
			}
		}, 0, 1, TimeUnit.MINUTES);
		timer.scheduleAtFixedRate(monitor, 0, 5, TimeUnit.SECONDS);

		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {/**/}
	}
}