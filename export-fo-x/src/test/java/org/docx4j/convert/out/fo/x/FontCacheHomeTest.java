package org.docx4j.convert.out.fo.x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.docx4j.Docx4jProperties;
import org.docx4j.openpackaging.parts.AbstractFontPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontCacheHomeTest {

	@Test
	void anExistingDirectoryResolvesAndIsLeftAlone(@TempDir Path home) {
		assertTrue(FontCacheHome.resolves(home.toString()));
	}

	@Test
	void theValuesAServerActuallyProducesDoNotResolve() {
		// A container with no passwd entry for the running UID: the JDK sets user.home
		// to "?". docx4j then skips its temp-directory fallback altogether and returns
		// the relative path ".docx4j".
		assertFalse(FontCacheHome.resolves("?"));
		assertFalse(FontCacheHome.resolves(null));
		assertFalse(FontCacheHome.resolves(""));
		assertFalse(FontCacheHome.resolves("   "));
		assertFalse(FontCacheHome.resolves("/nonexistent/definitely/not/here"));
	}

	@Test
	void aPlainFileCountsAsResolvable(@TempDir Path dir) throws IOException {
		// Deliberately mirrors docx4j, which tests exists() rather than isDirectory().
		// docx4j handles this case itself: the .docx4j mkdir fails and it falls back to
		// the temp directory, so there is nothing here for us to fix.
		File file = Files.createFile(dir.resolve("home")).toFile();
		assertTrue(FontCacheHome.resolves(file.getAbsolutePath()));
	}

	@Test
	void theCacheDirectoryIsCreatedInsideTheGivenBaseAndIsWritable(@TempDir Path base) {
		File directory = FontCacheHome.subdirectoryOf(base.toFile());

		assertNotNull(directory, "no usable directory created");
		assertTrue(directory.isDirectory());
		assertTrue(directory.canWrite());
		assertTrue(directory.getAbsolutePath().startsWith(base.toFile().getAbsolutePath()),
				"expected the directory under " + base + ", got " + directory);
	}

	@Test
	void theCacheDirectoryIsStableSoItSurvivesARestart(@TempDir Path base) {
		assertEquals(FontCacheHome.subdirectoryOf(base.toFile()),
				FontCacheHome.subdirectoryOf(base.toFile()));
	}

	@Test
	void anUnwritableBaseYieldsNothingRatherThanABrokenPath(@TempDir Path base) {
		File readOnly = base.resolve("locked").toFile();
		assertTrue(readOnly.mkdirs());
        assumeTrue(readOnly.setWritable(false), "cannot make a directory read-only here");

		assertNull(FontCacheHome.subdirectoryOf(readOnly));

		readOnly.setWritable(true); // so @TempDir can clean up
	}

	@Test
	void aNominatedDirectoryIsPreferredOverTheTempDirectories(@TempDir Path nominated) {
		// In a servlet container this is jakarta.servlet.context.tempdir, which the spec
		// requires to be writable -- a better bet than $CATALINA_BASE/temp.
		String original = System.getProperty("user.home");
		try {
			System.setProperty("user.home", "/nonexistent/definitely/not/here");

			assertTrue(FontCacheHome.ensureUsable(nominated.toFile()));
			assertTrue(System.getProperty("user.home")
					.startsWith(nominated.toFile().getAbsolutePath()));
		} finally {
			System.setProperty("user.home", original);
		}
	}

	@Test
	void aConfiguredEmbeddedFontDirectoryIsCreatedWithItsParents(@TempDir Path base) {
		// docx4j itself only calls mkdir(), so a nested path that does not exist yet stays
		// missing -- and the next conversion fails in deleteEmbeddedFontTempFiles.
		File nested = base.resolve("export-fo-x").resolve("embedded fonts").toFile();
		String original = Docx4jProperties.getProperty(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY);
		try {
			Docx4jProperties.setProperty(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY,
					nested.getAbsolutePath());

			FontCacheHome.ensureEmbeddedFontDirectoryConfigured(null);

			assertTrue(nested.isDirectory());
			assertEquals(nested.getAbsolutePath(),
					Docx4jProperties.getProperty(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY),
					"a usable configured directory must not be replaced");
		} finally {
			restore(original);
		}
	}

	@Test
	void anUnusableEmbeddedFontDirectoryIsRedirectedToTheNominatedOne(@TempDir Path base)
			throws IOException {
		// A plain file where the directory should be: neither mkdirs nor writing can work,
		// on any platform -- the stand-in for a root-owned home on RHEL.
		File blocked = Files.createFile(base.resolve("blocked")).toFile();
		File nominated = base.resolve("nominated").toFile();
		assertTrue(nominated.mkdirs());
		String original = Docx4jProperties.getProperty(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY);
		try {
			Docx4jProperties.setProperty(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY,
					new File(blocked, "fonts").getAbsolutePath());

			FontCacheHome.ensureEmbeddedFontDirectoryConfigured(nominated);

			File redirected = new File(
					Docx4jProperties.getProperty(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY));
			assertTrue(redirected.getAbsolutePath().startsWith(nominated.getAbsolutePath()),
					"expected the directory under " + nominated + ", got " + redirected);
			assertTrue(redirected.isDirectory());
			assertTrue(redirected.canWrite());
		} finally {
			restore(original);
		}
	}

	@Test
	void aVanishedEmbeddedFontDirectoryIsRecreated(@TempDir Path base) {
		File gone = base.resolve("cleaned up").resolve("temporary embedded fonts").toFile();

		FontCacheHome.recreate(gone);

		assertTrue(gone.isDirectory());
	}

	@Test
	void docx4jsEmbeddedFontDirectoryExistsOnceTheConverterHasRun() {
		FontCacheHome.ensureEmbeddedFontDirectory();

		File directory = AbstractFontPart.getTmpFontDir();
		assertNotNull(directory);
		assertTrue(directory.isDirectory(), directory + " does not exist");
	}

	private static void restore(String original) {
		if (original == null) {
			Docx4jProperties.getProperties().remove(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY);
		} else {
			Docx4jProperties.setProperty(FontCacheHome.EMBEDDED_FONT_DIR_PROPERTY, original);
		}
	}

	@Test
	void aWorkingUserHomeIsLeftUntouchedHoweverOftenItIsCalled() {
		String before = System.getProperty("user.home");

		// The build runs with a real home directory, so every call must be a no-op.
		assertFalse(FontCacheHome.ensureUsable(),
				"user.home resolves during the build, so nothing should have changed");
		assertFalse(FontCacheHome.ensureUsable(), "must stay idempotent");
		assertFalse(FontCacheHome.ensureUsable(new File(System.getProperty("java.io.tmpdir"))),
				"a nominated directory must not override a working user.home");

		assertEquals(before, System.getProperty("user.home"));
	}
}
