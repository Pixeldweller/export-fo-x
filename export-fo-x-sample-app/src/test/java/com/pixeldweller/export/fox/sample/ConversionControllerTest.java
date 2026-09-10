package com.pixeldweller.export.fox.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ConversionControllerTest {

	private static final String SAMPLE = "/sample/freigabe_antragsteller_auto.docx";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void answersWithThePdf() throws Exception {
		byte[] pdf = mockMvc.perform(multipart("/api/convert").file(sample()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
				.andExpect(header().string("Content-Disposition",
						org.hamcrest.Matchers.containsString("freigabe_antragsteller_auto.pdf")))
				.andReturn().getResponse().getContentAsByteArray();

		assertThat(pdf).isNotEmpty();
		try (PDDocument document = Loader.loadPDF(pdf)) {
			// Not an exact count: line breaking follows whatever font FOP finds, so a
			// machine without Open Sans repaginates the letter. What this test is about
			// is the HTTP round trip, so "more than one page" is the honest assertion.
			assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);

			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setEndPage(1);
			String page1 = stripper.getText(document);

			// The letterhead: proves the anchored VML shapes survived the round trip.
			assertThat(page1)
					.contains("Musterbehörde Musterstadt")
					.contains("Landeskasse Musterstadt");
		}
	}

	@Test
	void fillsThePlaceholdersGivenInTheValuesPart() throws Exception {
		byte[] pdf = mockMvc.perform(multipart("/api/convert").file(sample())
						.param("values", "{\"NACHNAME_ANTRAGSTELLER\":\"Sonderbach\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();

		try (PDDocument document = Loader.loadPDF(pdf)) {
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("Sonderbach");
			// The ones with no value given stay visible instead of going blank.
			assertThat(text).contains("$VORNAME_ANTRAGSTELLER$");
		}
	}

	@Test
	void rejectsValuesThatAreNotJson() throws Exception {
		mockMvc.perform(multipart("/api/convert").file(sample())
						.param("values", "{das ist kein json"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnEmptyUpload() throws Exception {
		MockMultipartFile empty = new MockMultipartFile("file", "empty.docx",
				null, new byte[0]);

		mockMvc.perform(multipart("/api/convert").file(empty))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsSomethingThatIsNotADocx() throws Exception {
		MockMultipartFile text = new MockMultipartFile("file", "notes.txt",
				"text/plain", "hello".getBytes());

		mockMvc.perform(multipart("/api/convert").file(text))
				.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void reportsAnUnreadableDocxAsUnprocessable() throws Exception {
		MockMultipartFile broken = new MockMultipartFile("file", "broken.docx",
				null, "this is not a zip".getBytes());

		mockMvc.perform(multipart("/api/convert").file(broken))
				.andExpect(status().isUnprocessableEntity());
	}

	private static MockMultipartFile sample() throws Exception {
		try (InputStream in = ConversionControllerTest.class.getResourceAsStream(SAMPLE)) {
			return new MockMultipartFile("file", "freigabe_antragsteller_auto.docx",
					"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
					in.readAllBytes());
		}
	}
}
