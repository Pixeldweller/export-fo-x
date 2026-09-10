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

## Betrieb ohne `user.home`

Läuft der Container unter einer beliebigen UID ohne passwd-Eintrag, setzt das JDK
`user.home` auf `"?"`. docx4j leitet daraus den Pfad seines Font-Caches ab und schreibt
ihn dann relativ ins Arbeitsverzeichnis – bei schreibgeschütztem Arbeitsverzeichnis mit
einem `ExceptionInInitializerError` beim ersten Laden von `IdentityPlusMapper`.

`main()` fängt das mit `FontCacheHome.ensureUsable()` ab, noch bevor Spring startet.
Alternativ oder zusätzlich reicht ein `-Duser.home=/tmp` beim JVM-Start.
