package com.pixeldweller.export.fox.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SampleTemplateControllerTest {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$[A-Za-z0-9_]+\\$");
	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@Test
	void listsEveryPlaceholderWithAFittingDefault() throws Exception {
		List<Map<String, String>> fields = fields();

		assertThat(fields).isNotEmpty();
		assertThat(fields).allSatisfy(f -> {
			assertThat(f.get("name")).isNotBlank();
			assertThat(f.get("label")).isNotBlank();
			assertThat(f.get("defaultValue")).isNotBlank();
		});

		// Body and header alike: the caseworker's details live in a header text box.
		assertThat(fields).extracting(f -> f.get("name"))
				.contains("NACHNAME_ANTRAGSTELLER", "TELEFON_SACHBEARBEITER",
						"STAATSANGEHOERIGKEIT_ANTRAGSTELLER");
	}

	@Test
	void fillingInEveryFieldLeavesNoPlaceholderInThePdf() throws Exception {
		Map<String, String> values = new HashMap<>();
		for (Map<String, String> field : fields()) {
			values.put(field.get("name"), field.get("defaultValue"));
		}

		String text = textOf(pdfFor(values));

		Matcher leftover = PLACEHOLDER.matcher(text);
		assertThat(leftover.find())
				.withFailMessage(() -> "placeholder left in the PDF: " + leftover.group())
				.isFalse();
	}

	@Test
	void theSubmittedValuesActuallyReachThePdf() throws Exception {
		String text = textOf(pdfFor(Map.of(
				"VORNAME_ANTRAGSTELLER", "Klara",
				"NACHNAME_ANTRAGSTELLER", "Sonderbach")));

		assertThat(text).contains("Klara").contains("Sonderbach");
	}

	@Test
	void anEmptyBodyStillRendersTheTemplate() throws Exception {
		// Nothing filled in: the placeholders stay visible rather than the request failing.
		String text = textOf(pdfFor(Map.of()));

		assertThat(PLACEHOLDER.matcher(text).find()).isTrue();
		assertThat(text).contains("Musterbescheid");
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, String>> fields() throws Exception {
		String body = mockMvc.perform(get("/api/sample/fields"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JSON.readValue(body, List.class);
	}

	private byte[] pdfFor(Map<String, String> values) throws Exception {
		return mockMvc.perform(post("/api/sample/pdf")
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(values)))
				.andExpect(status().isOk())
				.andExpect(header -> assertThat(header.getResponse().getContentType())
						.isEqualTo(MediaType.APPLICATION_PDF_VALUE))
				.andReturn().getResponse().getContentAsByteArray();
	}

	private static String textOf(byte[] pdf) throws Exception {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			return new PDFTextStripper().getText(document);
		}
	}
}
