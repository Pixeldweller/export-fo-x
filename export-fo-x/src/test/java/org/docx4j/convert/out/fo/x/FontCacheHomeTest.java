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
