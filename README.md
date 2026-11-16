# FotoTool

App Android personale per il Google Pixel 8 Pro: migliora in automatico le foto RAW (DNG) scattate con Pixel Camera.

## Come funziona
1. Scatti con Pixel Camera con RAW attivo (Impostazioni → Avanzate → RAW + JPEG).
2. FotoTool si accorge del nuovo DNG (anche ad app chiusa) e lo sviluppa con il suo motore:
   correzione dell'obiettivo letta dal DNG, bianco e colori calibrati in Display P3, rumore, luci e ombre per zone, nitidezza.
3. Usa il JPEG di Google come riferimento per l'esposizione.
4. Salva `DCIM/FotoTool/PXL_…_FotoTool.jpg` in **Ultra HDR**, alla stessa risoluzione del RAW, con i dati di scatto della foto di Google.
   L'originale non viene mai toccato.
5. Nell'app: tieni premuto su una foto per vedere l'originale di Google; scegli tra Naturale, Vivido, Morbido e Bianco e nero.
   FotoTool impara lo stile che preferisci per giorno, notte e controluce.

## Struttura
- `motore/`: sviluppo del RAW in Kotlin puro (lettore DNG e JPEG senza perdita, colore, zone, rumore, gain map). Si prova sul computer: `gradle :motore:test`.
- `app/`: app Android (Compose). `lavoro/` = osservatore della galleria, coda, elaborazione in background, archivio.
- `strumenti/`: script Python che generano i casi di prova del motore (`ljpeg_codifica.py`, `dng_sintetico.py`) e leggono i rapporti (`leggi_rapporti.py`).

## Rapporti
Dopo ogni foto e a ogni chiusura l'app salva un rapporto completo (parametri, tempi, memoria, miniature prima/dopo):
copia leggibile in `Download/FotoTool_report`, copia cifrata (RSA-OAEP-256 + AES-256-GCM) inviata al ricevitore
`rapporti-vincenzo` che la salva nel repository `FotoTool-rapporti`. La chiave privata è su Drive (`FOTOTOOL_RAPPORTI_CHIAVE.pem`).

## Compilazione
Ogni push su `main` esegue i test del motore, compila l'APK firmato e, se `versionCode` è nuovo, pubblica la Release.
Se la compilazione fallisce, l'errore va nel ramo `log-build`.
