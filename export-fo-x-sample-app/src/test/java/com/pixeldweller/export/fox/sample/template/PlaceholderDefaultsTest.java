package com.pixeldweller.export.fox.sample.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class PlaceholderDefaultsTest {

	@Test
	void theSpecificRuleWinsOverTheGeneralOne() {
		// GEBURTSDATUM also contains DATUM, and the caseworker's name also ends in a
		// name; whichever rule is checked first decides, so the order is the behaviour.
		assertThat(PlaceholderDefaults.forName("GEBURTSDATUM_ANTRAGSTELLER")).isEqualTo("17.03.1985");
		assertThat(PlaceholderDefaults.forName("VORNAME_SACHBEARBEITER")).isEqualTo("Alex");
		assertThat(PlaceholderDefaults.forName("VORNAME_ANTRAGSTELLER")).isEqualTo("Erika");
		assertThat(PlaceholderDefaults.forName("GEBURTSORT_ANTRAGSTELLER")).isEqualTo("Musterstadt");
	}

	@Test
	void aDecisionDateIsToday() {
		String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY));
		assertThat(PlaceholderDefaults.forName("ENTSCHEIDUNGS_DATUM")).isEqualTo(today);
	}

	@Test
	void theApplicationDateIsInThePast() {
		assertThat(PlaceholderDefaults.forName("ANTRAG_DATUM"))
				.isNotEqualTo(PlaceholderDefaults.forName("ENTSCHEIDUNGS_DATUM"));
	}

	@Test
	void anUnknownFieldFallsBackToItsOwnLabel() {
		// An empty box would leave the reader guessing what belongs in it.
		assertThat(PlaceholderDefaults.forName("VOELLIG_UNBEKANNT")).isEqualTo("Voellig Unbekannt");
	}

	@Test
	void labelsAreReadable() {
		assertThat(PlaceholderDefaults.labelFor("NACHNAME_ANTRAGSTELLER")).isEqualTo("Nachname Antragsteller");
		assertThat(PlaceholderDefaults.labelFor("PLZ")).isEqualTo("Plz");
		assertThat(PlaceholderDefaults.labelFor("A__B")).isEqualTo("A B");
	}

	@Test
	void matchingIsCaseInsensitive() {
		assertThat(PlaceholderDefaults.forName("plz_antragsteller")).isEqualTo("12345");
	}
}
