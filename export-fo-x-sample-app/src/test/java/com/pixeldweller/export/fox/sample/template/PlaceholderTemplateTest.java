package com.pixeldweller.export.fox.sample.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.Test;

class PlaceholderTemplateTest {

	@Test
	void findsAPlaceholderEvenWhenWordSplitItAcrossRuns() throws Exception {
		// Exactly how the sample template stores $STAATSANGEHOERIGKEIT_ANTRAGSTELLER$:
		// three runs, the middle one formatted differently, so docx4j's own joinupRuns
		// would not merge them either.
		WordprocessingMLPackage pkg = packageWith(
				paragraph(run("$STAAT", null), run("S", size(24)), run("ANGEHOERIGKEIT_X$", null)));

		List<Placeholder> found = PlaceholderTemplate.scan(pkg);

		assertThat(found).extracting(Placeholder::name).containsExactly("STAATSANGEHOERIGKEIT_X");
	}

	@Test
	void stampsAcrossTheRunBoundary() throws Exception {
		P paragraph = paragraph(run("$STAAT", null), run("S", size(24)), run("ANGEHOERIGKEIT_X$", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		assertThat(PlaceholderTemplate.stamp(pkg, Map.of("STAATSANGEHOERIGKEIT_X", "deutsch"))).isEqualTo(1);
		assertThat(textOf(paragraph)).isEqualTo("deutsch");
	}

	@Test
	void keepsTheTextAroundThePlaceholder() throws Exception {
		P paragraph = paragraph(run("Sehr geehrte $NAME$, guten Tag", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		PlaceholderTemplate.stamp(pkg, Map.of("NAME", "Frau Mustermann"));

		assertThat(textOf(paragraph)).isEqualTo("Sehr geehrte Frau Mustermann, guten Tag");
	}

	@Test
	void replacesEveryOccurrenceOfTheSamePlaceholder() throws Exception {
		P paragraph = paragraph(run("$N$ und $N$ und $N$", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		assertThat(PlaceholderTemplate.stamp(pkg, Map.of("N", "x"))).isEqualTo(3);
		assertThat(textOf(paragraph)).isEqualTo("x und x und x");
	}

	@Test
	void handlesTwoPlaceholdersWithNoTextBetweenThem() throws Exception {
		// The sample writes $TITEL_ANTRAGSTELLER$$VORNAME_ANTRAGSTELLER$ exactly like this.
		P paragraph = paragraph(run("$A$$B$", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		PlaceholderTemplate.stamp(pkg, Map.of("A", "Dr. ", "B", "Erika"));

		assertThat(textOf(paragraph)).isEqualTo("Dr. Erika");
	}

	@Test
	void aPlaceholderWithNoValueIsLeftStanding() throws Exception {
		// Better a visible $UNKNOWN$ in the PDF than a silently empty line.
		P paragraph = paragraph(run("$KNOWN$ / $UNKNOWN$", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		assertThat(PlaceholderTemplate.stamp(pkg, Map.of("KNOWN", "hier"))).isEqualTo(1);
		assertThat(textOf(paragraph)).isEqualTo("hier / $UNKNOWN$");
	}

	@Test
	void anEmptyValueIsAValueAndClearsThePlaceholder() throws Exception {
		P paragraph = paragraph(run("[$A$]", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		PlaceholderTemplate.stamp(pkg, Map.of("A", ""));

		assertThat(textOf(paragraph)).isEqualTo("[]");
	}

	@Test
	void aValueContainingDollarsDoesNotSendTheStamperInCircles() throws Exception {
		P paragraph = paragraph(run("$A$", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		PlaceholderTemplate.stamp(pkg, Map.of("A", "$A$"));

		assertThat(textOf(paragraph)).isEqualTo("$A$");
	}

	@Test
	void stampingNothingChangesNothing() throws Exception {
		P paragraph = paragraph(run("$A$", null));
		WordprocessingMLPackage pkg = packageWith(paragraph);

		assertThat(PlaceholderTemplate.stamp(pkg, Map.of())).isZero();
		assertThat(PlaceholderTemplate.stamp(pkg, null)).isZero();
		assertThat(textOf(paragraph)).isEqualTo("$A$");
	}

	@Test
	void listsPlaceholdersInTheOrderTheyAppearAndOnlyOnce() throws Exception {
		WordprocessingMLPackage pkg = packageWith(paragraph(run("$B$ $A$ $B$", null)));

		assertThat(PlaceholderTemplate.scan(pkg)).extracting(Placeholder::name)
				.containsExactly("B", "A");
	}

	// -- helpers ---------------------------------------------------------------------

	private static WordprocessingMLPackage packageWith(P... paragraphs) throws Exception {
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		for (P p : paragraphs) {
			pkg.getMainDocumentPart().getContent().add(p);
		}
		return pkg;
	}

	private static P paragraph(R... runs) {
		P p = new P();
		for (R r : runs) {
			p.getContent().add(r);
		}
		return p;
	}

	private static R run(String text, RPr properties) {
		Text t = new Text();
		t.setValue(text);
		R r = new R();
		r.setRPr(properties);
		r.getContent().add(t);
		return r;
	}

	private static RPr size(int halfPoints) {
		HpsMeasure sz = new HpsMeasure();
		sz.setVal(BigInteger.valueOf(halfPoints));
		RPr rPr = new RPr();
		rPr.setSz(sz);
		return rPr;
	}

	private static String textOf(P paragraph) {
		StringBuilder sb = new StringBuilder();
		for (Object o : paragraph.getContent()) {
			for (Object c : ((R) o).getContent()) {
				sb.append(((Text) org.docx4j.XmlUtils.unwrap(c)).getValue());
			}
		}
		return sb.toString();
	}
}
