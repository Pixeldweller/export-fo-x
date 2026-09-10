# export-fo-x (Bibliothek)

Drop-in-Ersatz für `docx4j-export-fo` 11.5.9: gleiche Packages, gleiche Ressourcenpfade.
`Docx4J.toPDF(pkg, out)` funktioniert unverändert und nutzt alle Korrekturen automatisch.

`docx4j-export-fo` darf dann nicht zusätzlich im Classpath liegen – die Klassen gäbe es
sonst doppelt.

## Was neu ist

Geänderte docx4j-Klassen (Herkunft steht jeweils im Dateikopf):

| Datei | Änderung |
|---|---|
| `docx2fo.xslt` | jedes `w:pict` geht an den PictWriter statt nur die drei bekannten Formen |
| `FOPictWriterAbstract` | erkennt `v:line`, ungefüllte Formen und Formen ohne Textbox |
| `FOPictWriterNoWrapImpl` | vollständige `mso-position-*`-Auflösung, keine Debug-Rahmen |
| `FOPictWriterFloatUsed/-Avoided` | Rahmen nur noch, wenn die Form einen deklariert |
| `FOPAreaTreeHelper` | `NumberFormatException` bei fehlendem `@bpda`, `int`-Divisionen, Casts |
| `FOConversionImageHandler` | WMF/EMF-Konvertierung beim Herausschreiben |
| `AbstractFOExporter` | ruft die FO-Nachbearbeitung auf |

Neu, in `org.docx4j.convert.out.fo.x`:

| Klasse | Aufgabe |
|---|---|
| `VmlStyle` | robuster Parser für das VML-`@style`-Attribut |
| `VmlAnchor` | `mso-position-*` → Koordinaten ab der Seitenecke |
| `VmlFoBuilder` | erzeugt den `fo:block-container` samt Füllung, Rahmen, Inset |
| `FoAbsolutePositionHoister` | hebt absolute Container aus `fo:inline` (FOP verwirft sie dort) |
| `MetafileConverter` | WMF → SVG, EMF → eingebettetes Raster |
| `SectPrParagraphNormalizer` | Abschnittswechsel auf einen eigenen Absatz |
| `LineSpacingNormalizer` | „Mehrfach"-Zeilenabstand an die Schriftmetrik binden |
| `LeadingTabNormalizer` | führenden Tab in Absätzen mit hängendem Einzug zum Erstzeilen-Einzug auflösen |
| `FontLineHeights` | misst die natürliche Zeilenhöhe einer Schrift |
| `FontCacheHome` | sorgt für ein auflösbares `user.home`, bevor der Font-Cache geschrieben wird |

Fassade in `com.pixeldweller.export.fox`: `DocxToPdfConverter` und `ConversionOptions`.

Die vollständige Fehleranalyse steht im [README des Gesamtprojekts](../README.md).
