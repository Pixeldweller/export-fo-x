/*
 * export-fo-x -- Weiterentwicklung von docx4j-export-fo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.docx4j.convert.out.fo.x;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for the CSS-like {@code @style} attribute VML shapes carry, eg
 *
 * <pre>
 * position:absolute;margin-left:467.75pt;margin-top:147.35pt;width:113.4pt;height:644.8pt;
 * z-index:-251654144;mso-position-horizontal:absolute;mso-position-horizontal-relative:page
 * </pre>
 *
 * <p>docx4j's own parser (FOPictWriterAbstract#getProperties) splits every entry on
 * {@code ":"} and then indexes {@code parts[1]} unconditionally, so an entry without a
 * colon (or an empty entry produced by a trailing/duplicated {@code ";"}) raises
 * ArrayIndexOutOfBoundsException, and a value that itself contains a colon is truncated.
 * This parser is total: it never throws on real-world input.
 */
public final class VmlStyle {

	private static final Logger log = LoggerFactory.getLogger(VmlStyle.class);

	/** Conversion factors to points, keyed by CSS unit suffix. */
	private static final Map<String, Double> UNITS_TO_PT = Map.of(
			"pt", 1.0,
			"in", 72.0,
			"mm", 72.0 / 25.4,
			"cm", 72.0 / 2.54,
			"pc", 12.0,
			"px", 72.0 / 96.0,
			"em", 12.0,   // rough: assumes a 12pt em
			"ex", 6.0);

	private final Map<String, String> props;

	private VmlStyle(Map<String, String> props) {
		this.props = props;
	}

	/** Parses a VML {@code @style} attribute. A null or blank style yields an empty instance. */
	public static VmlStyle parse(String style) {
		if (style == null || style.isBlank()) {
			return new VmlStyle(Collections.emptyMap());
		}
		Map<String, String> map = new LinkedHashMap<>();
		for (String entry : style.split(";")) {
			if (entry.isBlank()) {
				continue;
			}
			int colon = entry.indexOf(':');
			if (colon < 0) {
				// eg a bare "visible"; keep it as a valueless flag rather than failing
				map.put(normaliseKey(entry), "");
				continue;
			}
			// Only the FIRST colon separates key from value, so values such as
			// "url:http://..." or "mso-wrap-style:square" survive intact.
			String key = normaliseKey(entry.substring(0, colon));
			String value = entry.substring(colon + 1).trim();
			if (!key.isEmpty()) {
				map.put(key, value);
			}
		}
		return new VmlStyle(map);
	}

	private static String normaliseKey(String key) {
		return key.trim().toLowerCase(Locale.ROOT);
	}

	public boolean isEmpty() {
		return props.isEmpty();
	}

	/** Raw value, or null. */
	public String get(String key) {
		return props.get(normaliseKey(key));
	}

	/** Raw value lower-cased, or {@code fallback} when absent/blank. */
	public String get(String key, String fallback) {
		String v = get(key);
		return (v == null || v.isBlank()) ? fallback : v.toLowerCase(Locale.ROOT);
	}

	public boolean has(String key) {
		return props.containsKey(normaliseKey(key));
	}

	/**
	 * Length in points, or null when the property is absent or unparseable.
	 * Understands pt/in/mm/cm/pc/px/em/ex; a bare number is read as CSS pixels.
	 */
	public Double lengthPt(String key) {
		return toPoints(get(key));
	}

	/** Length in points, or {@code fallback} when absent/unparseable. */
	public double lengthPt(String key, double fallback) {
		Double v = lengthPt(key);
		return v == null ? fallback : v;
	}

	/** Parses a single CSS-ish length into points. Returns null if it cannot be read. */
	public static Double toPoints(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty()) {
			return null;
		}
		for (Map.Entry<String, Double> unit : UNITS_TO_PT.entrySet()) {
			if (s.endsWith(unit.getKey())) {
				return parseDouble(s.substring(0, s.length() - unit.getKey().length()), unit.getValue(), raw);
			}
		}
		// No unit: CSS says pixels.
		return parseDouble(s, UNITS_TO_PT.get("px"), raw);
	}

	private static Double parseDouble(String number, double factor, String raw) {
		try {
			return Double.parseDouble(number.trim()) * factor;
		} catch (NumberFormatException e) {
			log.debug("Not a length: '{}'", raw);
			return null;
		}
	}

	/** Integer value (eg z-index), or null. */
	public Integer intValue(String key) {
		String v = get(key);
		if (v == null) {
			return null;
		}
		try {
			return Integer.valueOf(v.trim());
		} catch (NumberFormatException e) {
			log.debug("Not an integer: {}='{}'", key, v);
			return null;
		}
	}

	/** The parsed properties, in document order. Unmodifiable. */
	public Map<String, String> asMap() {
		return Collections.unmodifiableMap(props);
	}

	@Override
	public String toString() {
		return props.toString();
	}
}
