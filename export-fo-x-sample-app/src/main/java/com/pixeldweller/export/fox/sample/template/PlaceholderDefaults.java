/*
 * export-fo-x sample application.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pixeldweller.export.fox.sample.template;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Guesses a sensible value for a placeholder from its name, so the generated form comes
 * up filled in and can be submitted as-is.
 *
 * <p>Rules are tried in order and the first match wins, so the specific ones come before
 * the general: {@code GEBURTSDATUM} has to be seen before {@code DATUM}, and the
 * caseworker's name before the applicant's.
 */
public final class PlaceholderDefaults {

	private static final DateTimeFormatter GERMAN_DATE =
			DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

	/** Ordered: the first key the name contains decides. */
	private static final Map<String, Supplier<String>> RULES = new LinkedHashMap<>();

	static {
		// Dates -- the specific ones first
		RULES.put("GEBURTSDATUM", () -> "17.03.1985");
		RULES.put("ENTSCHEIDUNGS_DATUM", () -> LocalDate.now().format(GERMAN_DATE));
		RULES.put("ANTRAG_DATUM", () -> LocalDate.now().minusWeeks(6).format(GERMAN_DATE));
		RULES.put("DATUM", () -> LocalDate.now().format(GERMAN_DATE));

		// Places of birth, before the general address rules
		RULES.put("GEBURTSORT", () -> "Musterstadt");
		RULES.put("GEBURTSBUNDESLAND", () -> "Musterland");
		RULES.put("GEBURTSLAND", () -> "Deutschland");
		RULES.put("GEBURTSNAME", () -> "Musterfrau");

		// People -- caseworker before applicant, since both end in _SACHBEARBEITER
		RULES.put("VORNAME_SACHBEARBEITER", () -> "Alex");
		RULES.put("NACHNAME_SACHBEARBEITER", () -> "Mustermann");
		RULES.put("TELEFON_SACHBEARBEITER", () -> "01234 567-42");
		RULES.put("EMAIL_SACHBEARBEITER", () -> "alex.mustermann@example.org");

		RULES.put("ANREDE", () -> "Frau");
		RULES.put("BEGRUESSUNG", () -> "geehrte");
		RULES.put("TITEL", () -> "Dr. ");
		RULES.put("VORNAME", () -> "Erika");
		RULES.put("NACHNAME", () -> "Mustermann");

		// Address
		RULES.put("ADRESSE_ZUSATZ", () -> "c/o Musterfirma GmbH");
		RULES.put("STRASSE", () -> "Musterweg 1");
		RULES.put("PLZ", () -> "12345");
		RULES.put("ORT", () -> "Musterstadt");

		// Misc
		RULES.put("STAATSANGEHOERIGKEIT", () -> "deutsch");
		RULES.put("AKTENZEICHEN", () -> "MU 12/34-5678");
		RULES.put("TELEFON", () -> "01234 567-0");
		RULES.put("EMAIL", () -> "post@example.org");
	}

	private PlaceholderDefaults() {
	}

	/**
	 * @return a suggested value, or the placeholder's own name when no rule fits -- an
	 *         empty box would leave the reader guessing what belongs in it
	 */
	public static String forName(String placeholderName) {
		String upper = placeholderName.toUpperCase(Locale.ROOT);
		for (Map.Entry<String, Supplier<String>> rule : RULES.entrySet()) {
			if (upper.contains(rule.getKey())) {
				return rule.getValue().get();
			}
		}
		return labelFor(placeholderName);
	}

	/** {@code NACHNAME_ANTRAGSTELLER} becomes {@code Nachname Antragsteller}. */
	public static String labelFor(String placeholderName) {
		StringBuilder sb = new StringBuilder();
		for (String word : placeholderName.split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(word.charAt(0)))
			  .append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return sb.length() == 0 ? placeholderName : sb.toString();
	}
}
