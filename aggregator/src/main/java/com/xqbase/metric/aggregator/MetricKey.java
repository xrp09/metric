package com.xqbase.metric.aggregator;

import java.util.HashMap;

public class MetricKey {
	private String name;
	private HashMap<String, String> tagMap;

	public MetricKey(String name, HashMap<String, String> tagMap) {
		this.name = name;
		this.tagMap = tagMap;
	}

	public MetricKey(String name, String... tagPairs) {
		this.name = name;
		tagMap = new HashMap<>();
		for (int i = 0; i < tagPairs.length - 1; i += 2) {
			tagMap.put(tagPairs[i], tagPairs[i + 1]);
		}
	}

	public String getName() {
		return name;
	}

	public HashMap<String, String> getTagMap() {
		return tagMap;
	}

	@Override
	public boolean equals(Object obj) {
		MetricKey metricKey = (MetricKey) obj;
		return metricKey.name.equals(name) && metricKey.tagMap.equals(tagMap);
	}

	@Override
	public int hashCode() {
		return name.hashCode() + tagMap.hashCode();
	}
}