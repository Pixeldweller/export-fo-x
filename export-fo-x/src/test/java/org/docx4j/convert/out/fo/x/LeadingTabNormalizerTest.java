package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTTabStop;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.R;
import org.docx4j.wml.STTabJc;
import org.docx4j.wml.Tabs;
import org.docx4j.wml.Text;
import org.junit.jupiter.api.Test;

/**
 * The pattern under test is the German "Verfügung" paragraph style: a hanging indent
 * pulls the first line out to the left, and a tab at the start of the run puts it back.
 * docx4j applies the indent but turns every tab into three fixed spaces, so the
 * compensation is lost.
 */
class LeadingTabNormalizerTest {

	/** 10mm, the hanging indent the sample letter's style uses. */
	private static final BigInteger HANGING = BigInteger.valueOf(567);

	@Test
	void aTabBackToTheIndentCancelsTheHangingIndent() throws Exception {
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		P paragraph = paragraph(HANGING, BigInteger.ZERO, tabStopAt(0));
		pkg.getMainDocumentPart().getContent().add(paragraph);

		assertEquals(1, LeadingTabNormalizer.normalize(pkg));

		// Net effect in Word: the line starts at the left indent, like every other line.
		assertEquals(0, indentOf(paragraph).getHanging().intValue());
		assertNull(indentOf(paragraph).getFirstLine());
		assertFalse(startsWithTab(paragraph), "the leading tab should have been consumed");
	}

	@Test
	void aStopBeforeTheIndentLeavesTheRestOfTheHang() throws Exception {
		// Line starts at 720 - 567 = 153; the stop at 360 is the next one along, so the
		// first line ends up 360 twips before the 720 indent.
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		P paragraph = paragraph(HANGING, BigInteger.valueOf(720), tabStopAt(360));
		pkg.getMainDocumentPart().getContent().add(paragraph);

		assertEquals(1, LeadingTabNormalizer.normalize(pkg));
		assertEquals(360, indentOf(paragraph).getHanging().intValue());
	}

	@Test
	void withoutAnyStopTheHangingIndentImpliesOneAtTheLeftIndent() throws Exception {
		// Word treats a hanging indent as an implicit tab stop at the left indent.
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		P paragraph = paragraph(HANGING, BigInteger.ZERO, null);
		pkg.getMainDocumentPart().getContent().add(paragraph);

		assertEquals(1, LeadingTabNormalizer.normalize(pkg));
		assertEquals(0, indentOf(paragraph).getHanging().intValue());
	}

	@Test
	void aParagraphWithoutALeadingTabIsLeftAlone() throws Exception {
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		P paragraph = paragraph(HANGING, BigInteger.ZERO, tabStopAt(0));
		removeLeadingTab(paragraph);
		pkg.getMainDocumentPart().getContent().add(paragraph);

		assertEquals(0, LeadingTabNormalizer.normalize(pkg));
		assertEquals(HANGING, indentOf(paragraph).getHanging());
	}

	@Test
	void aLeadingTabWithoutAHangingIndentIsLeftAlone() throws Exception {
		// An ordinary tab still has to render as a tab, whatever docx4j makes of it.
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		P paragraph = paragraph(null, BigInteger.ZERO, tabStopAt(1440));
		pkg.getMainDocumentPart().getContent().add(paragraph);

		assertEquals(0, LeadingTabNormalizer.normalize(pkg));
		assertTrue(startsWithTab(paragraph));
	}

	@Test
	void aRightAlignedStopIsNotSomethingAnIndentCanExpress() throws Exception {
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		CTTabStop right = tabStopAt(2000).getTab().get(0);
		right.setVal(STTabJc.RIGHT);
		Tabs tabs = new Tabs();
		tabs.getTab().add(right);

		// Only a right stop, and the left indent is where the line already starts, so
		// there is no usable stop at all.
		P paragraph = paragraph(HANGING, BigInteger.ZERO, tabs);
		pkg.getMainDocumentPart().getContent().add(paragraph);

		// The implicit stop at the left indent still applies, so this one is handled;
		// what matters is that the right stop at 2000 was not used for it.
		assertEquals(1, LeadingTabNormalizer.normalize(pkg));
		assertEquals(0, indentOf(paragraph).getHanging().intValue());
	}

	@Test
	void runningTwiceDoesNotStripASecondTab() throws Exception {
		WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
		P paragraph = paragraph(HANGING, BigInteger.ZERO, tabStopAt(0));
		paragraph.getContent().add(runWith(new R.Tab(), text("zweiter Tab")));
		pkg.getMainDocumentPart().getContent().add(paragraph);

		assertEquals(1, LeadingTabNormalizer.normalize(pkg));
		assertEquals(0, LeadingTabNormalizer.normalize(pkg), "must be idempotent");
		assertEquals(0, indentOf(paragraph).getHanging().intValue());
	}

	// -- helpers ---------------------------------------------------------------------

	private static P paragraph(BigInteger hanging, BigInteger left, Tabs tabs) {
		PPrBase.Ind ind = new PPrBase.Ind();
		ind.setHanging(hanging);
		ind.setLeft(left);

		PPr pPr = new PPr();
		pPr.setInd(ind);
		pPr.setTabs(tabs);

		P paragraph = new P();
		paragraph.setPPr(pPr);
		paragraph.getContent().add(runWith(new R.Tab(), text("Text")));
		return paragraph;
	}

	private static Tabs tabStopAt(int twips) {
		CTTabStop stop = new CTTabStop();
		stop.setVal(STTabJc.LEFT);
		stop.setPos(BigInteger.valueOf(twips));
		Tabs tabs = new Tabs();
		tabs.getTab().add(stop);
		return tabs;
	}

	private static R runWith(Object... content) {
		R run = new R();
		for (Object o : content) {
			run.getContent().add(o);
		}
		return run;
	}

	private static Text text(String value) {
		Text t = new Text();
		t.setValue(value);
		return t;
	}

	private static PPrBase.Ind indentOf(P paragraph) {
		return paragraph.getPPr().getInd();
	}

	private static R firstRun(P paragraph) {
		return (R) XmlUtils.unwrap(paragraph.getContent().get(0));
	}

	private static boolean startsWithTab(P paragraph) {
		R run = firstRun(paragraph);
		return !run.getContent().isEmpty()
				&& XmlUtils.unwrap(run.getContent().get(0)) instanceof R.Tab;
	}

	private static void removeLeadingTab(P paragraph) {
		firstRun(paragraph).getContent().remove(0);
	}
}
