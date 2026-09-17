package com.pixeldweller.export.fox.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fonts live at {@code test-fonts/} on the test classpath -- deliberately not
 * {@code fonts/}, which docx4j scans itself.
 */
class FontResourceExtractorTest {

	private static final String FONT = "LiberationSans-Regular.ttf";

	@Test
	void copiesFontsOutOfTheClasspath(@TempDir Path target) throws Exception {
		File result = FontResourceExtractor.extract("test-fonts", target.toFile());

		assertThat(result).isEqualTo(target.toFile());
		assertThat(target.resolve(FONT)).exists();
		assertThat(Files.size(target.resolve(FONT))).isGreaterThan(1000);
	}

	@Test
	void theExtractedFileIsUsableAsAFontAgain() throws Exception {
		// The whole point: back to a real file, so java.awt can measure the line height
		// that export-fo-x needs for Word's "multiple" line spacing. A jar: URI cannot.
		Path target = Files.createTempDirectory("fontprobe");
		FontResourceExtractor.extract("test-fonts", target.toFile());

		java.awt.Font font = java.awt.Font.createFont(
				java.awt.Font.TRUETYPE_FONT, target.resolve(FONT).toFile());

		assertThat(font.getFamily()).containsIgnoringCase("Liberation");
	}

	@Test
	void aSecondRunReusesWhatIsAlreadyThere() throws Exception {
		Path target = Files.createTempDirectory("fontreuse");

		FontResourceExtractor.extract("test-fonts", target.toFile());
		long firstWrite = Files.getLastModifiedTime(target.resolve(FONT)).toMillis();

		Thread.sleep(20);
		FontResourceExtractor.extract("test-fonts", target.toFile());

		assertThat(Files.getLastModifiedTime(target.resolve(FONT)).toMillis())
				.isEqualTo(firstWrite);
	}

	@Test
	void aChangedFontIsWrittenAgain(@TempDir Path target) throws Exception {
		// Same name, wrong size: must be replaced rather than trusted.
		Files.writeString(target.resolve(FONT), "not a font");

		FontResourceExtractor.extract("test-fonts", target.toFile());

		assertThat(Files.size(target.resolve(FONT))).isGreaterThan(1000);
	}

	@Test
	void anAbsentResourcePathIsNotAnError(@TempDir Path target) {
		// An application that ships no fonts must simply start.
		assertThat(FontResourceExtractor.extract("no-such-directory", target.toFile())).isNull();
		assertThat(FontResourceExtractor.extract("", target.toFile())).isNull();
		assertThat(FontResourceExtractor.extract(null, target.toFile())).isNull();
	}

	@Test
	void surroundingSlashesAreTolerated(@TempDir Path target) {
		assertThat(FontResourceExtractor.extract("/test-fonts/", target.toFile())).isNotNull();
	}

	@Test
	void nonFontFilesAreIgnored(@TempDir Path target) throws Exception {
		FontResourceExtractor.extract("sample", target.toFile());

		// sample/ holds the .docx template, no fonts.
		assertThat(target).isEmptyDirectory();
	}
}
