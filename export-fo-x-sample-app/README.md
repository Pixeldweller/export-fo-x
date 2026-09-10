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
| `400` | leerer oder unlesbarer Upload |
| `415` | keine `.docx`-Datei |
| `422` | DOCX ließ sich nicht konvertieren |

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
