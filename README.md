# export-fo-x

Fork und Weiterentwicklung von **docx4j-export-fo 11.5.9** (DOCX → PDF über XSL-FO und
Apache FOP), damit Word-Briefbögen („Kopfbogen") im PDF tatsächlich ankommen.

Die Version 11.5.9 ist bewusst fixiert, weil Plutext Enterprise in Version 11 im Einsatz
ist. Ziel-Laufzeit: **JDK 17**, Spring Boot 3.5.x.

```
export-fo-x/
├── export-fo-x/              Bibliothek  – Drop-in-Ersatz für docx4j-export-fo
├── export-fo-x-sample-app/   Spring Boot – nimmt ein DOCX, antwortet mit PDF
└── samples/                  Beispielvorlage und die beiden Konvertierungsergebnisse
```

In `samples/` liegen zum direkten Vergleich:

| Datei | |
|---|---|
| `freigabe_antragsteller_auto.docx` | die Vorlage (anonymisiert, siehe unten) |
| `freigabe_antragsteller_auto-docx4j-11.5.9.pdf` | mit unverändertem docx4j – ohne Kopfbogen |
| `freigabe_antragsteller_auto-export-fo-x.pdf` | mit export-fo-x, bei registriertem Open Sans |

### Anonymisierung

Die Vorlage stammt aus einem echten Behördenverfahren. Für dieses Repository sind
ersetzt:

* Behördenname, Anschriften, Telefonnummern, Bankverbindung und Aktenzeichen durch eine
  fiktive „Musterbehörde Musterstadt",
* die Dokumenteigenschaften (dort standen zwei Klarnamen),
* das Behördenlogo durch ein generiertes Platzhalter-WMF mit derselben Bounding Box,
* der **Fließtext des Briefes** durch neu geschriebenen Platzhaltertext – nicht
  paraphrasiert, sondern neu formuliert: die längste wörtliche Übereinstimmung mit dem
  Original beträgt zwei Wörter. Die Absatzlängen sind auf 3 % genau nachgebildet, damit
  Umbruch und Seitenaufteilung vergleichbar bleiben.

Stehen geblieben sind generische Formularbeschriftungen („Angaben zur Person",
„Familienname") und die `$VARIABLE$`-Platzhalter: beide sagen nichts über eine Behörde
aus, und ohne sie wäre die Datei als Vorlagenbeispiel wertlos.

Unangetastet blieb, worauf es technisch ankommt: **jedes VML-`@style` und jeder
Shape-Offset**. Die Koordinaten weiter unten sind deshalb unverändert gültig – sie hängen
nicht am Text.

Das Ursprungsmaterial (`export-fo.log` mit Benutzer- und Pfadangaben, die entpackte
Original-Vorlage und das Word-PDF) liegt weiterhin im Arbeitsverzeichnis, ist aber über
`.gitignore` vom Repository ausgenommen.

---

## Das Problem

Beim Konvertieren von `freigabe_antragsteller_auto.docx` mit docx4j 11.5.9 fehlte im
PDF **der komplette Kopfbogen**: die Anschriftenspalte am rechten Rand, der Titel
„Musterbehörde Musterstadt", das Behördenlogo und die beiden Faltmarken.

Im Log stand nur:

```
WARN  AbstractConversionContext   - NOT IMPLEMENTED: support for w:pict;  without v:imagedata
WARN  FOPictWriterNoWrapImpl      - No support for mso_position_horizontal_relative==false
ERROR FOPAreaTreeHelper           - For input string: ""
      java.lang.NumberFormatException: For input string: ""
WARN  FOUserAgent                 - The contents of fo:region-before on page 2 exceed the
                                    available area in the block-progression direction …
```

Der Kopfbogen besteht aus fünf VML-Shapes im Header, und **jedes einzelne** ist an einer
anderen Stelle verloren gegangen:

| Element im Kopfbogen | VML | Was docx4j damit machte |
|---|---|---|
| 2 Faltmarken | `v:line` | verworfen – „NOT IMPLEMENTED … without v:imagedata" |
| Anschriftenspalte rechts | `v:shape` + `v:textbox`, `…-relative:page` | Container ohne `width`/`left` erzeugt |
| Titel | `v:shape` + `v:textbox`, `…-relative:text` | Container in `fo:inline` → von FOP verworfen |
| Behördenlogo | WMF in `v:textbox` | FOP zeichnet WMF leer |
| Header-Höhe | – | `NumberFormatException`, Extent aus Zufallswerten |

---

## Die Ursachen im Einzelnen

### 1. `w:pict` ohne `v:imagedata` wurde weggeworfen

`docx2fo.xslt` kannte genau drei Fälle: `v:shape/v:imagedata`, `v:shape/v:textbox` und
`v:rect/v:textbox`. Alles andere – insbesondere `v:line` und ungefüllte `v:rect` – lief in
`notImplemented(…)`. Ein Briefbogen besteht größtenteils aus genau diesen Formen.

**Jetzt:** Jedes `w:pict` geht an `FOPictWriter`; Java entscheidet, was die Form ist.
`v:line` wird zu einer einseitigen Rahmenlinie (bzw. zu Inline-SVG, wenn sie wirklich
diagonal ist), gefüllte/umrandete Formen werden zu `fo:block-container`.

### 2. Seitenbezogene Positionierung war nicht implementiert

`FOPictWriterNoWrapImpl` behandelte ausschließlich
`mso-position-horizontal-relative:text`. Bei jedem anderen Wert loggte es eine Warnung –
und setzte **weder `width` noch `left`**. Die Anschriftenspalte landete deshalb ohne
Breite irgendwo im Header.

**Jetzt:** `VmlAnchor` löst die komplette `mso-position-*`-Familie in Koordinaten ab
der linken oberen Seitenecke auf: `page`, `margin`, `text`, `column`, `char` und die vier
`*-margin-area`-Werte, dazu die Ausrichtungen `absolute|left|center|right|inside|outside`.
Ist die Vertikale seitenbezogen auflösbar, entsteht ein
`fo:block-container absolute-position="fixed"`; hängt sie am Textfluss, bleibt es beim
bisherigen Verhalten.

### 3. FOP verwirft absolut positionierte Container in `fo:inline` – lautlos

Das war der teuerste Fund. Die XSLT verpackt jedes `w:r` in ein `fo:inline`, also landet
auch ein aus `w:pict` erzeugter Container dort. FOP wirft ihn dann weg: **keine Warnung,
kein Fehler, kein Output.** Direkt unter einem `fo:block` funktioniert derselbe Container
einwandfrei (verifiziert gegen FOP 2.11).

**Jetzt:** `FoAbsolutePositionHoister` hebt solche Container einmalig aus den `fo:inline`
heraus, direkt bevor die FO an den Renderer geht.

### 4. WMF/EMF: FOP nimmt sie an und zeichnet nichts

FOP akzeptiert eine WMF ohne Murren und reserviert exakt den richtigen Platz – aber
Batiks WMF-Transcoder implementiert nur einen kleinen Teil der GDI-Operationen, und das
Logo bleibt leer.

**Jetzt:** `MetafileConverter` wandelt WMF mit `wmf2svg` (ohnehin schon docx4j-Dependency)
nach SVG um, das FOP über Batiks SVG-Pfad korrekt rendert. Bei EMF – wofür es gar keinen
reinen Java-Renderer gibt – wird die größte eingebettete Rastergrafik extrahiert; sonst
gibt es eine klare Warnung statt eines stillen Leerbilds.

### 5. Header-Höhe: `NumberFormatException` bei jeder Form

`FOPAreaTreeHelper.calculateHFExtents` las `Integer.parseInt(block.getAttribute("bpda"))`
ungeprüft. Ein außerhalb des Textflusses liegender Block hat gar kein `@bpda` – also
`parseInt("")` – und zwar für jede Form auf jeder Seite.

**Jetzt:** Blöcke ohne `@bpda` zählen mit 0 (out of flow gehört korrekterweise nicht in
die Region-Höhe). Dazu behoben: mehrere `int`-Divisionen, die Bruchteile von Punkten
verschluckten, und ungeprüfte `(Element)`-Casts auf Whitespace-Textknoten.

### 6. Debug-Rahmen um jede Textbox

`FOPictWriterAbstract.setBorders()` zog *immer* einen durchgezogenen Rahmen um jede
Textbox – „so we can see the text box". Word zeichnet einen nur bei `stroked="t"`.

**Jetzt:** Rahmen und Füllung kommen aus `stroked`/`strokecolor`/`strokeweight`/
`filled`/`fillcolor`; `v:textbox/@inset` und `v-text-anchor` werden ausgewertet.

### 7. Der Absatz mit dem Abschnittswechsel rutschte eine Seite weiter

Nach ECMA-376 §17.6.17 ist ein Absatz mit `w:pPr/w:sectPr` der **letzte** Absatz seines
Abschnitts. `ConversionSectionWrapperFactory` schließt den Abschnitt aber *vor* diesem
Absatz und hängt ihn an den folgenden – der Schlusssatz des Briefes landete auf Seite 2.

**Jetzt:** `SectPrParagraphNormalizer` verschiebt das `sectPr` auf einen eigenen, 1pt
hohen Markierungsabsatz dahinter. Der Text bleibt, wo er hingehört. Bewusst *kein*
Überschatten einer docx4j-core-Klasse – das hinge an der Classpath-Reihenfolge und
würde auch die HTML-Ausgabe verändern.

### 8. Zeilenabstand „Mehrfach" war um den Faktor der Schriftmetrik falsch

`<w:spacing w:line="360" w:lineRule="auto"/>` heißt 1,5 **Zeilen**, und eine Zeile ist in
Word die Höhe der Schrift (Ascent + Descent + Line Gap). docx4j ignoriert `w:lineRule`
komplett und schreibt `line-height="150%"` – ein Prozentwert bezieht sich in XSL-FO aber
auf die **Schriftgröße**. Bei Open Sans (natürliche Zeilenhöhe 1,36 em) werden aus
Word-16,3pt so 12pt: der Text ist ein Viertel zu eng, und ab Seite 2 stimmt der Umbruch
nicht mehr.

**Jetzt:** `LineSpacingNormalizer` multipliziert `w:line` vor der Konvertierung mit dem
Zeilenhöhen-Verhältnis der Schrift. Das passiert auf der WML-Ebene, weil dort `w:lineRule`
noch lesbar ist – aus `line-height="150%"` in der fertigen FO ließe sich nicht mehr
erkennen, ob „1,5 Zeilen" oder „exakt 18pt" gemeint war. Schriften, die auf der Maschine
fehlen, bleiben unangetastet.

---

### 9. Ohne `user.home` schreibt docx4j den Font-Cache in einen relativen Pfad

Auf einem Server ohne `user.home` – ein Container, der unter einer beliebigen UID ohne
passwd-Eintrag läuft, wo das JDK `user.home` auf `"?"` setzt – leitet docx4j den Pfad des
Font-Caches ausschließlich daraus ab (`FontCache.getDefaultCacheFile`):

```java
File dir = toDirectory(System.getProperty("user.home"));   // null, außer es EXISTIERT
if (dir != null) {
    File d = new File(dir, ".docx4j");
    if (!d.exists()) writable = d.mkdir();
    if (!writable) dir = toDirectory(System.getProperty("java.io.tmpdir"));  // ← tmp-Fallback
    return new File(new File(dir, ".docx4j"), "fop-fonts.cache");
}
return new File(".docx4j");    // ← relativ
```

Der tmp-Fallback greift nur, wenn `user.home` **existiert**, aber nicht beschreibbar ist.
Existiert es gar nicht, wird der ganze Block übersprungen und die Methode liefert den
**relativen** Pfad `.docx4j`, aufgelöst gegen das Arbeitsverzeichnis des Prozesses. Eine
eigene Property zum Umbiegen gibt es nicht – `user.home` ist die einzige Eingabe.

Und es bleibt nicht bei einer verirrten Datei: `FontCache.saveTo` legt keine
Elternverzeichnisse an und meldet Fehler strikt, und `IdentityPlusMapper.<clinit>` ruft
`fontCache.save()` unbedingt auf und wirft alles Gefangene weiter. Bei einem
schreibgeschützten Arbeitsverzeichnis ist das Ergebnis:

```
Exception in thread "main" java.lang.ExceptionInInitializerError
Caused by: java.lang.RuntimeException: org.docx4j.fonts.fop.apps.FOPException:
           java.io.FileNotFoundException: .docx4j (Permission denied)
```

Danach ist `IdentityPlusMapper` für die Lebensdauer des Classloaders unbrauchbar.

**Jetzt:** [`FontCacheHome.ensureUsable()`](export-fo-x/src/main/java/org/docx4j/convert/out/fo/x/FontCacheHome.java)
biegt `user.home` auf ein Verzeichnis unter `java.io.tmpdir` um – aber nur, wenn es sich
nicht auflösen lässt. Ein vorhandenes, nur nicht beschreibbares `user.home` bleibt
unangetastet, denn diesen Fall deckt docx4j selbst ab.

Aufgerufen wird das aus `DocxToPdfConverter` und aus der `main()` der Beispielanwendung.
**Die Reihenfolge zählt:** der Cache wird aus einem Static-Initializer geschrieben, der
Aufruf muss also erfolgen, bevor `org.docx4j.fonts.IdentityPlusMapper` das erste Mal
geladen wird. Wer `Docx4J.toPDF` direkt benutzt, ruft `FontCacheHome.ensureUsable()` beim
Start selbst auf – oder startet die JVM schlicht mit `-Duser.home=/tmp` bzw. `%TEMP%`.
Abschalten lässt sich der Eingriff mit `-Dexportfox.userHomeFallback.disabled=true`.

---

### 10. Führender Tab in Absätzen mit hängendem Einzug ging verloren

Der Anschriftenblock benutzt den Stil `Verfügung` – ein Muster, das in jedem
Behördenbrief steckt:

```xml
<w:style w:styleId="Verfgung">
  <w:pPr>
    <w:tabs><w:tab w:val="left" w:pos="0"/></w:tabs>   <!-- Tabstopp am Rand -->
    <w:ind w:hanging="567"/>                            <!-- 10 mm hängend -->
  </w:pPr>
</w:style>
```

Der Run beginnt mit `<w:tab/>`: Word startet die erste Zeile 567 Twips links vom Einzug,
der Tab springt auf den Tabstopp bei 0 – netto keine Einrückung, die Zeile steht bündig
mit allen anderen. `docx2fo.xslt` übernimmt den Einzug, macht aus **jedem** `w:tab` aber
drei geschützte Leerzeichen, unabhängig von den Tabstopps des Absatzes:

```xml
<!--  Use this simple-minded approach from MS stylesheet,
      until our document model can do better.   -->
<xsl:with-param name="count" select="3"/>
```

Die erste Zeile des Anschriftenblocks hing dadurch 18 pt nach links heraus.

**Jetzt:** XSL-FO kennt keine Tabstopps, auf die man das abbilden könnte, also wird es im
WML aufgelöst: [`LeadingTabNormalizer`](export-fo-x/src/main/java/org/docx4j/convert/out/fo/x/LeadingTabNormalizer.java)
entfernt den führenden Tab und setzt den Erstzeilen-Einzug auf die Position, an der der
Tab gelandet wäre. Den Tabstopp sucht er wie Word: der nächste linke Stopp hinter dem
Zeilenanfang, wobei ein hängender Einzug einen impliziten Stopp am linken Einzug
beisteuert. Angefasst werden nur Absätze mit **beidem** – hängendem Einzug *und*
führendem Tab –, also genau die, die heute falsch aussehen. In der Beispielvorlage ist
das exakt ein Absatz; alle übrigen 90 Zeilen stehen unverändert an derselben Stelle.

Rechts- und zentrierte Tabstopps bleiben unangetastet: die lassen sich nicht als Einzug
ausdrücken.

---

## Ergebnis

Gemessen gegen das PDF, das Microsoft Word aus derselben Vorlage erzeugt hat. Die Spalte
„vorher" ist ein echter Lauf mit dem unveränderten `docx4j-export-fo-11.5.9.jar` und
denselben registrierten Schriften (Koordinaten in pt ab der linken oberen Seitenecke):

| | Word | export-fo-x | vorher (docx4j 11.5.9) |
|---|---|---|---|
| Faltmarke 1 | (−4,90 / 297,20) | (−4,90 / 297,20) | fehlt |
| Faltmarke 2 | (−4,90 / 423,20) | (−4,90 / 423,20) | fehlt |
| Logo, linke Kante | 461,85 | 461,85 | fehlt |
| Anschriftenspalte, „Telefon" | x = 467,86 | x = 467,75 | fehlt |
| Titel im Kopfbogen | x = 283,6 | x = 283,6 | fehlt |
| Anschriftenblock, erste Zeile | x = 68,06 | x = 68,40 | x = 50,33 |
| Seiten | 3 | 3 | 3 |
| Wörter Fließtext Seite 1 / 2 / 3 | 77 / 146 / 27 | 77 / 146 / 27 | 53 / 191 / 0 |
| Anschriftenspalte auf Seite 1 | vorhanden | vorhanden | fehlt |

Die Wortzahlen stammen aus der Originalvorlage, **vor** dem Ersetzen des Fließtextes –
nur so lassen sie sich gegen das Word-PDF halten, das es für den Platzhaltertext
naturgemäß nicht gibt. Mit der ausgelieferten Vorlage in `samples/` ergibt sich heute:

| | export-fo-x | vorher (docx4j 11.5.9) |
|---|---|---|
| Seiten | 3 | 3 |
| Wörter Fließtext Seite 1 / 2 / 3 | 79 / 155 / 31 | 56 / 203 / 0 |
| Wörter Anschriftenspalte Seite 1 | 72 | 0 |
| Faltmarken | (−4,90 / 297,20), (−4,90 / 423,20) | fehlen |

Die Struktur ist dieselbe wie bei Word: Seite 1 endet mit dem Absatz, der den
Abschnittswechsel trägt, Seite 3 enthält den Schlussabsatz. Der unveränderte docx4j
verliert dagegen den kompletten Kopfbogen und schiebt einen Absatz auf die Folgeseite.

Die Koordinaten stammen aus den VML-`@style`-Angaben und sind vom Textinhalt unabhängig;
sie gelten für die ausgelieferte Vorlage unverändert.

Verbleibende bekannte Abweichungen sind unter „Grenzen" beschrieben.

---

## Bibliothek benutzen

`export-fo-x` ist ein **Drop-in-Ersatz**: gleiche Packages, gleiche Ressourcenpfade.
`Docx4J.toPDF(pkg, out)` funktioniert unverändert weiter und nutzt automatisch alle
Korrekturen. Wichtig ist nur, dass `docx4j-export-fo` nicht zusätzlich im Classpath
liegt – sonst gäbe es dieselben Klassen doppelt.

```xml
<dependency>
  <groupId>com.pixeldweller.export</groupId>
  <artifactId>export-fo-x</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Falls docx4j-export-fo transitiv hereinkommt: -->
<dependency>
  <groupId>org.docx4j</groupId>
  <artifactId>docx4j-core</artifactId>
  <version>11.5.9</version>
  <exclusions>
    <exclusion>
      <groupId>org.docx4j</groupId>
      <artifactId>docx4j-export-fo</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

Bequemer ist die mitgelieferte Fassade – sie erledigt zusätzlich das Registrieren der
Schriften und die beiden WML-Normalisierungen:

```java
DocxToPdfConverter converter = new DocxToPdfConverter(
        new ConversionOptions()
            .addFontDirectory(new File("/opt/app/fonts")));

try (InputStream in  = new FileInputStream("brief.docx");
     OutputStream out = new FileOutputStream("brief.pdf")) {
    converter.convert(in, out);
}
```

### Betrieb im Tomcat / nur `/tmp` beschreibbar

Vier Stellen wollen schreiben, alle abgeleitet aus `user.home` bzw. `java.io.tmpdir` –
und unter Tomcat ist `java.io.tmpdir` nicht `/tmp`, sondern `$CATALINA_BASE/temp`.
Schriften, die im WAR stecken, brauchen zudem eine Sonderbehandlung, weil eine
`jar:`-URI die Zeilenhöhen-Messung ausschaltet. Beides ist in der
[README der Anwendung](export-fo-x-sample-app/README.md#betrieb-im-tomcat) beschrieben.

### Schriften

**Das ist der wichtigste Betriebsparameter.** Findet docx4j eine Schrift nicht, wird sie
ersetzt – und weil der Zeilenumbruch den Metriken der Ersatzschrift folgt, fließt das
ganze Dokument neu, oft auf eine andere Seitenzahl. Auf der Windows-Maschine, auf der die
Vorlagen entstanden sind, ist alles da; in einem Linux-Container in der Regel nichts.

Die Beispielvorlage braucht **Open Sans**, **Arial** und **Times New Roman**. Open Sans
ist unter der SIL Open Font License frei verteilbar; für Arial und Times New Roman sind
die metrikkompatiblen Liberation-Schriften (`fonts-liberation`) der übliche Ersatz.

`LineSpacingNormalizer` greift ebenfalls nur bei Schriften, die tatsächlich vorhanden
sind – ein weiterer Grund, sie mitzuliefern.

---

## Beispielanwendung

```bash
mvn -pl export-fo-x-sample-app -am spring-boot:run
```

```bash
curl -F file=@samples/freigabe_antragsteller_auto.docx \
     http://localhost:8080/api/convert -o brief.pdf
```

Unter <http://localhost:8080/> liegt zusätzlich ein Formular: Upload von Hand, und –
aus den `$PLATZHALTERN$` der Beispielvorlage generiert – ein ausfüllbares Feldformular
mit passenden Vorgabewerten. Details in der
[README der Anwendung](export-fo-x-sample-app/README.md).

| Antwort | Bedeutung |
|---|---|
| `200` + `application/pdf` | konvertiert |
| `400` | leerer oder unlesbarer Upload |
| `415` | keine `.docx`-Datei |
| `422` | DOCX ließ sich nicht konvertieren |

Konfiguration in `application.yml`:

```yaml
export-fo-x:
  font-directories:
    - /opt/export-fo-x/fonts
  normalize-section-breaks: true   # Abschnittswechsel-Absatz korrekt zuordnen
  normalize-line-spacing:   true   # „Mehrfach"-Zeilenabstand an Schriftmetrik binden
  fo-dump-directory: ""            # gesetzt: schreibt zusätzlich die Zwischen-FO
```

Läuft eine Konvertierung schief, ist der schnellste Weg: `fo-dump-directory` setzen,
`logging.level.org.docx4j: DEBUG` und `org.apache.fop: INFO` einschalten und in die FO
schauen.

---

## Branches

| Branch | |
|---|---|
| `main` | Hauptlinie |
| `docx4j-v11` | Stand für docx4j 11.x / Plutext Enterprise 11 – **aktuell ausgecheckt** |

Beide zeigen derzeit auf denselben Commit. Die Version 11.5.9 ist an mehreren Stellen
fest verdrahtet – als `docx4j.version` in der Parent-POM, aber auch inhaltlich: die
geforkten Klassen entsprechen genau diesem Stand, und die beschriebenen Fehler sind gegen
ihn verifiziert. Ein Sprung auf docx4j 12 gehört deshalb in einen eigenen Branch
(`docx4j-v12`) und nicht in eine Property-Änderung.

---

## Bauen

Beide Module zusammen:

```bash
mvn clean install
```

Getestet mit Maven 3.9.9 und Temurin JDK 17.0.20.1.

Die Tests umfassen die Einheiten (VML-Style-Parser, Positionsauflösung, Metafile-Erkennung,
FO-Nachbearbeitung) sowie zwei Ende-zu-Ende-Tests, die die Beispielvorlage wirklich
konvertieren und im PDF nachweisen, dass der Kopfbogen vorhanden und richtig platziert ist.
Die erwarteten Koordinaten stammen aus dem PDF, das Microsoft Word aus derselben
Vorlage erzeugt hat; dieses PDF selbst liegt nicht im Repository, weil es den
nicht anonymisierten Kopfbogen enthält.

---

## Grenzen

* **`v:group` wird nicht unterstützt.** Gruppierte VML-Formen haben ein eigenes
  Koordinatensystem (`coordsize`/`coordorigin`); sie werden mit einer deutlichen Warnung
  übersprungen. In Word aufzulösen ist der Weg.
* **EMF ohne eingebettete Rastergrafik bleibt leer.** Für EMF existiert kein reiner
  Java-Renderer. Das Bild in der Vorlage als PNG oder SVG neu einzufügen ist die
  verlässliche Lösung.
* **`w:lineRule="exact"` und `"atLeast"`** übersetzt docx4j ebenfalls falsch (es behandelt
  Twips wie 240stel-Zeilen). Das bleibt unverändert: eine Korrektur bräuchte die effektive
  Schriftgröße je Zeile und würde mehr Dokumente verändern als reparieren.
* **Textboxen werden nicht beschnitten.** Word schneidet den Inhalt am Rahmen ab; hier
  darf er überlaufen. FOPs Zeilenumbruch ist nicht Words Zeilenumbruch, und eine
  abgeschnittene letzte Zeile ist schlimmer als eine überstehende.
* **Zeilenumbruch bei sehr langen Platzhaltern.** Tokens wie
  `$STAATSANGEHOERIGKEIT_ANTRAGSTELLER$` bricht FOP nicht um und sie laufen aus ihrer
  Zelle. Mit echten Daten tritt das nicht auf.

---

## Lizenz

**Apache License 2.0** – siehe [LICENSE](LICENSE) und [NOTICE](NOTICE).

Das ist keine freie Wahl, sondern die Lizenz von docx4j, aus dem der geforkte Code
stammt. Apache-2.0 erlaubt Fork, Änderung und Weiterverbreitung ausdrücklich, knüpft das
aber an Bedingungen, die hier so erfüllt sind:

| Apache-2.0 | Umsetzung |
|---|---|
| §4(a) Lizenzkopie beilegen | [LICENSE](LICENSE) |
| §4(b) geänderte Dateien kennzeichnen | Hinweis im Kopf jeder der 8 geänderten Dateien |
| §4(c) Copyright-Vermerke erhalten | Original-Header von Plutext in allen 31 übernommenen Dateien |
| §4(d) NOTICE weiterreichen | keine der Abhängigkeiten liefert eine NOTICE aus; [NOTICE](NOTICE) nennt die Herkunft trotzdem |

Von den 31 Dateien aus `docx4j-export-fo` 11.5.9 sind 23 unverändert und 8 geändert.
`FOPAreaTreeHelper.java` kam schon stromaufwärts ohne Lizenzkopf; der Header wurde beim
Forken ergänzt, weil die Datei aus dem Apache-2.0-lizenzierten Artefakt stammt.

Der Code unter `org/docx4j/convert/out/fo/x/` ist neu. Er liegt bewusst im
docx4j-Namensraum: export-fo-x ist ein Drop-in-Ersatz und muss Package- und
Ressourcenpfade beibehalten, damit `Docx4J.toPDF` unverändert funktioniert.

### Abhängigkeiten

Das Repository verteilt keine Abhängigkeiten, es deklariert sie nur – daraus entsteht
keine Pflicht. Ein **paketiertes Artefakt** dagegen schon: das
`spring-boot-maven-plugin` baut ein Fat-JAR mit allen Abhängigkeiten darin. Wer so eines
veröffentlicht, verbreitet sie mit. Alle sind permissiv lizenziert (Apache-2.0 bzw. MIT);
die Font-JARs enthalten Schriften unter eigenen Bedingungen, unter anderem die
DejaVu-Fonts unter der Bitstream-Vera-Lizenz. [NOTICE](NOTICE) listet das auf.

### Beispielvorlage

`samples/freigabe_antragsteller_auto.docx` enthält keinen Text aus der Originalvorlage
mehr: Behördenangaben, Logo **und Fließtext** sind durch Platzhalter ersetzt. Was bleibt,
ist die Struktur – VML-Shapes im Header, hängender Einzug, Mehrfach-Zeilenabstand – und
genau die ist der technische Gegenstand dieses Projekts.
