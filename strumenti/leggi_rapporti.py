"""Decifra i rapporti di FotoTool (cartella del repository FotoTool-rapporti) con la chiave privata.

Uso: python3 leggi_rapporti.py CHIAVE.pem cartella_o_file [... ] [--miniature cartella_uscita]  (miniature e ritagli al 100%)
Ogni file del ricevitore è un array di {key, iv, data}: chiave AES-256 cifrata con RSA-OAEP-SHA256, dati AES-GCM.
"""
import base64, json, os, sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def decifra(priv, voce):
    k = priv.decrypt(base64.b64decode(voce["key"]), padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    testo = AESGCM(k).decrypt(base64.b64decode(voce["iv"]), base64.b64decode(voce["data"]), None)
    return json.loads(testo)


def file_da(percorsi):
    for p in percorsi:
        if os.path.isdir(p):
            for r, _, nomi in os.walk(p):
                for n in sorted(nomi):
                    if n.endswith(".json"):
                        yield os.path.join(r, n)
        else:
            yield p


def main():
    args = sys.argv[1:]
    mini = None
    if "--miniature" in args:
        i = args.index("--miniature"); mini = args[i + 1]; del args[i:i + 2]
        os.makedirs(mini, exist_ok=True)
    priv = serialization.load_pem_private_key(open(args[0], "rb").read(), password=None)
    for f in file_da(args[1:]):
        for n, voce in enumerate(json.load(open(f))):
            r = decifra(priv, voce)
            m = (r.get("dettagli") or {}).pop("miniature", None)
            rit = (r.get("dettagli") or {}).pop("ritagli", None)
            if m and mini:
                for tipo, b in m.items():
                    if b:
                        open(os.path.join(mini, f"{os.path.basename(f)[:-5]}_{n}_{tipo}.jpg"), "wb").write(base64.b64decode(b))
            if isinstance(rit, list):
                for z in rit:
                    for tipo in ("prima", "dopo"):
                        if mini and z.get(tipo):
                            open(os.path.join(mini, f"{os.path.basename(f)[:-5]}_{n}_{z['zona']}_{tipo}.jpg"), "wb").write(base64.b64decode(z.pop(tipo)))
                        else:
                            z.pop(tipo, None)
                r["dettagli"]["ritagli"] = rit
            print(f"### {f} [{n}]")
            print(json.dumps(r, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
