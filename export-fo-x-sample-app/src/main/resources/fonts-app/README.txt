Hier hineingelegte Schriftdateien (.ttf, .otf, .ttc) werden beim Start in ein
beschreibbares Verzeichnis ausgepackt und bei docx4j registriert.

Warum ausgepackt und nicht direkt gelesen:

  * ConversionOptions.addFontDirectory listet ein Verzeichnis auf. In einem
    gepackten WAR gibt es keines.
  * docx4j kann Fonts zwar aus einem Jar lesen (PhysicalFonts.discoverJarFonts),
    registriert sie dann aber unter einer jar:-URI. export-fo-x misst die echte
    Zeilenhöhe der Schrift, um Words "Mehrfach"-Zeilenabstand nachzubilden, und
    dafür braucht es eine Datei, die es öffnen kann. Bei einer jar:-URI gibt die
    Messung auf und der Zeilenabstand fällt still auf das falsche
    docx4j-Verhalten zurück.

Warum der Ordner nicht "fonts" heißt: docx4j durchsucht diesen Namen selbst,
nimmt dabei aber nur den ersten Treffer im Klassenpfad. Ein Ordner "fonts" in
der Anwendung würde den in docx4j-export-fo-fonts-crosextra verdecken.

Die Beispielvorlage nennt Open Sans, Arial und Times New Roman. Open Sans steht
unter der SIL Open Font License und darf mitgeliefert werden; für Arial und
Times New Roman sind die metrikkompatiblen Liberation-Schriften der übliche
Ersatz. Absichtlich liegen hier keine Schriftdateien im Repository.

Konfiguration: export-fo-x.font-resource-path (Standard "fonts-app"),
export-fo-x.font-extract-directory.
