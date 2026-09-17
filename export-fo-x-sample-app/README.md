# export-fo-x-sample-app

Spring Boot 3.5 / JDK 17. Nimmt ein DOCX entgegen, antwortet mit dem PDF.

```bash
mvn -pl export-fo-x-sample-app -am spring-boot:run

curl -F file=@../samples/freigabe_antragsteller_auto.docx \
     http://localhost:8080/api/convert -o brief.pdf
```

Upload-Formular: <http://localhost:8080/>

| Antwort | Bedeutung |
|---|---|
| `200` + `application/pdf` | konvertiert |
| `400` | leerer oder unlesbarer Upload, oder `values` ist kein JSON |
| `415` | keine `.docx`-Datei |
| `422` | DOCX ließ sich nicht konvertieren |

## Platzhalter füllen

Eine Vorlage mit `$PLATZHALTERN$` lässt sich beim Konvertieren gleich befüllen.

### Eigenes DOCX

`POST /api/convert` nimmt neben `file` einen optionalen Teil `values` – ein
JSON-Objekt aus Platzhaltername und Wert:

```bash
curl -F file=@brief.docx \
     -F 'values={"NACHNAME_ANTRAGSTELLER":"Sonderbach","VORNAME_ANTRAGSTELLER":"Klara"}' \
     http://localhost:8080/api/convert -o brief.pdf
```

Platzhalter ohne Eintrag bleiben stehen. Das ist Absicht: ein halb ausgefülltes
Formular soll im PDF sichtbar sein und nicht als stille Leerstelle durchrutschen.

### Mitgelieferte Vorlage als Formular

```bash
curl http://localhost:8080/api/sample/fields
```

liefert alle Platzhalter der Beispielvorlage, jeweils mit lesbarer Beschriftung und
einem **passenden Vorgabewert**, der aus dem Feldnamen abgeleitet wird:

```json
[ { "name": "NACHNAME_ANTRAGSTELLER", "label": "Nachname Antragsteller",
    "defaultValue": "Mustermann" },
  { "name": "GEBURTSDATUM_ANTRAGSTELLER", "label": "Geburtsdatum Antragsteller",
    "defaultValue": "17.03.1985" },
  { "name": "ENTSCHEIDUNGS_DATUM", "label": "Entscheidungs Datum",
    "defaultValue": "10.09.2026" } ]
```

Dieselbe Struktur zurück an `POST /api/sample/pdf` (als flaches JSON-Objekt
`{"name": "wert"}`) ergibt das fertige PDF. Genau das macht auch das Formular auf
der Startseite: „Felder laden", Werte anpassen, „PDF erzeugen".

### Warum das nicht mit Suchen-und-Ersetzen geht

Word zerlegt Runs, wo es will – bei einem Rechtschreibmarker, einer Revisions-ID,
einem Formatwechsel –, ohne Rücksicht auf den Textinhalt. In der Beispielvorlage
steht `$STAATSANGEHOERIGKEIT_ANTRAGSTELLER$` als drei Runs:

```xml
<w:r><w:t>$STAAT</w:t></w:r>
<w:r><w:t>S</w:t></w:r>
<w:r><w:t>ANGEHOERIGKEIT_ANTRAGSTELLER$</w:t></w:r>
```

Wer `w:t` einzeln durchsucht, findet diesen Platzhalter nie. docx4js eigenes
`VariablePrepare.joinupRuns` hilft nur bedingt, weil es ausschließlich Runs mit
**identischer** Formatierung zusammenfasst. `PlaceholderTemplate` arbeitet deshalb
auf dem Gesamttext des Absatzes und schreibt das Ergebnis über die betroffenen
`w:t`-Elemente zurück; der Wert übernimmt die Formatierung des ersten davon.

Kopf- und Fußzeilen werden mitdurchsucht – in der Beispielvorlage stehen Telefon,
E-Mail und Datum des Sachbearbeiters in einer Textbox im Kopfbogen.

## Konfiguration

```yaml
export-fo-x:
  font-directories:
    - /opt/export-fo-x/fonts
  normalize-section-breaks: true
  normalize-line-spacing:   true
  fo-dump-directory: ""
```

`font-directories` ist der wichtigste Eintrag: eine Schrift, die docx4j nicht findet,
wird ersetzt, und weil der Zeilenumbruch den Metriken der Ersatzschrift folgt, fließt das
ganze Dokument neu. Die Beispielvorlage braucht Open Sans, Arial und Times New Roman.

Geht eine Konvertierung schief: `fo-dump-directory` setzen, `logging.level.org.docx4j`
auf `DEBUG` und `org.apache.fop` auf `INFO` stellen, in die Zwischen-FO schauen.

## Betrieb im Tomcat

```bash
mvn -Pwar package      # -> target/export-fo-x-sample-app-<version>.war
```

Das WAR ist zusätzlich mit `java -jar` startbar. Quellen sind für beide Modi dieselben:
`ExportFoXSampleApplication` erweitert `SpringBootServletInitializer`.

### Was schreiben will – und wohin

Vier Dinge legen Dateien an, alle abgeleitet aus `user.home` bzw. `java.io.tmpdir`:

| Wer | Pfad |
|---|---|
| docx4js FOP-Font-Cache | `<user.home>/.docx4j/fop-fonts.cache` |
| Apache FOPs eigener Font-Cache | `<user.home>/.fop/fop-fonts.cache` |
| Im DOCX eingebettete Schriften | `<user.home>/.docx4j/temporary embedded fonts/` |
| Beim Konvertieren extrahierte Bilder | `java.io.tmpdir`, überschreibbar mit `image-dir-path` |

**Dafür sind keine JVM-Optionen nötig.** Die Anwendung regelt das selbst, was wichtig ist,
wenn man auf dem Server nur das Artefakt austauschen darf:

* `user.home` wird im Static-Block von `ExportFoXSampleApplication` geprüft und, falls es
  sich nicht auflöst, auf das erste beschreibbare Verzeichnis gebogen – in dieser
  Reihenfolge: das vom Servlet-Container gemeldete Temp-Verzeichnis, dann
  `java.io.tmpdir`, dann `/tmp`. Der Static-Block statt `main()`, weil **ein WAR `main()`
  nie aufruft**; und zusätzlich noch einmal aus `onStartup`, wo
  `jakarta.servlet.context.tempdir` verfügbar ist – laut Servlet-Spec garantiert
  beschreibbar.
* Ausgepackte Schriften und extrahierte Bilder landen standardmäßig im Temp-Verzeichnis
  der Webanwendung (`$CATALINA_BASE/work/…/<app>/`), nicht in `$CATALINA_BASE/temp`.

Ein vorhandenes, funktionierendes `user.home` wird **nicht** angetastet – der Eingriff
passiert nur, wenn es ohnehin kaputt ist. Auf einem geteilten Tomcat verändert die
Anwendung damit nichts, worauf eine andere sich verlassen könnte.

Nachgemessen in einem echten Tomcat 10.1, deployt als WAR, mit `-Duser.home=?` (so setzt
es das JDK ohne passwd-Eintrag) und sonst **keiner** Option:

```
user.home was '?', which does not exist … Pointed user.home at $CATALINA_BASE/temp/docx4j-home-<user>
Fonts from classpath:fonts-app available in $CATALINA_BASE/work/Catalina/localhost/<app>/export-fo-x-fonts
Registered 4 font file(s)
→ HTTP 200, 3 Seiten, OpenSans-Regular eingebettet, 0 Platzhalter übrig
```

Und derselbe Lauf mit **schreibgeschütztem `$CATALINA_BASE/temp`**:

```
user.home was '?', which does not exist … Pointed user.home at /tmp/docx4j-home-<user>
→ HTTP 200, 3 Seiten, beide Caches unter /tmp/docx4j-home-<user>/{.docx4j,.fop}
```

Falls doch etwas festzulegen ist, geht das über `application.yml` im WAR – ganz ohne
Zugriff auf den Server:

```yaml
export-fo-x:
  font-extract-directory: /tmp/export-fo-x/fonts
  image-dir-path: /tmp/export-fo-x/images
```

Für das Verzeichnis der eingebetteten Schriften gibt es keinen eigenen Eintrag; es folgt
`user.home`. Wer es getrennt setzen will, kann in der mitgelieferten `docx4j.properties`
`docx4j.openpackaging.parts.WordprocessingML.ObfuscatedFontPart.tmpFontDir` angeben.

### Schriften im WAR

Schriftdateien unter `src/main/resources/fonts-app/` werden beim Start in ein
beschreibbares Verzeichnis ausgepackt und dort registriert. Das ist nicht Bequemlichkeit,
sondern nötig:

* `addFontDirectory` listet ein Verzeichnis auf – in einem gepackten WAR gibt es keines.
* docx4j kann Fonts zwar aus einem Jar lesen (`PhysicalFonts.discoverJarFonts`),
  registriert sie dann aber unter einer `jar:`-URI. export-fo-x misst die echte
  Zeilenhöhe der Schrift, um Words „Mehrfach"-Zeilenabstand nachzubilden, und braucht
  dafür eine öffenbare Datei. Nachgemessen: bei einer `jar:`-URI liefert die Messung
  `1,0000` – also „unbekannt, nichts ändern" –, und der Zeilenabstand fällt **still** auf
  das falsche docx4j-Verhalten zurück.

Der Ordner heißt absichtlich nicht `fonts`: docx4j durchsucht diesen Namen selbst, nimmt
dabei aber nur den **ersten** Treffer im Klassenpfad. Nachgemessen: liegt ein eigenes
`fonts/` vor `docx4j-export-fo-fonts-crosextra`, findet `discoverJarFonts()` nur noch die
eigene Schrift statt der acht aus dem Jar. Der Extraktor hier benutzt Springs
`classpath*:`-Resolver, der **alle** Treffer findet und auch mit Tomcats `war:`-Protokoll
für ein nicht entpacktes WAR umgeht.

Verifiziert mit einem gepackten WAR, leerem Temp-Verzeichnis und nicht existierendem
`user.home`: Fonts ausgepackt, registriert, PDF mit eingebettetem Open Sans und
korrekter Seitenaufteilung (3 Seiten – ohne die Schrift wären es 2).
