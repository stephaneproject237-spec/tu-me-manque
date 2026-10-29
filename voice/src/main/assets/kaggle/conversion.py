# ---- Take my voice : transformer un enregistrement (ou un texte) avec une voix entraînée ----
import base64

verifier_gpu()

etape("Recherche de la voix")
pth = glob.glob("/kaggle/input/**/model.pth", recursive=True)
idx = glob.glob("/kaggle/input/**/model.index", recursive=True)
if not pth or not idx:
    echec("NO_MODEL", "La voix entraînée est introuvable.")

HAUTEUR = int(PARAMS.get("hauteur", 0))
entree = TMP + "/entree.wav"
if PARAMS.get("mode") == "texte":
    if not PARAMS.get("texte", "").strip():
        echec("NO_AUDIO", "Texte vide.")
else:
    brut = TMP + "/entree_brute"
    if PARAMS.get("audio"):
        with open(brut, "wb") as f:
            f.write(base64.b64decode(PARAMS["audio"]))
    else:  # gros fichier : envoyé à part comme jeu de données
        trouves = glob.glob("/kaggle/input/**/entree_tmv.*", recursive=True)
        if not trouves:
            echec("NO_AUDIO", "Enregistrement introuvable.")
        brut = trouves[0]
    if not vers_wav(brut, entree, 44100):
        echec("NO_AUDIO", "Enregistrement illisible.")

installer_applio()

etape("Transformation de la voix")
sortie = TMP + "/sortie.wav"
reglages = ["--pth-path", pth[0], "--index-path", idx[0], "--pitch", HAUTEUR,
            "--index-rate", PARAMS.get("index", 0.75), "--protect", 0.33,
            "--volume-envelope", 1.0, "--f0-method", "rmvpe", "--export-format", "WAV"]
if PARAMS.get("mode") == "texte":
    with open(TMP + "/texte.txt", "w", encoding="utf-8") as f:
        f.write(PARAMS["texte"])
    applio(["tts", "--tts-file", TMP + "/texte.txt", "--tts-text", PARAMS["texte"][:500],
            "--tts-voice", PARAMS.get("voix_tts", "fr-FR-HenriNeural"),
            "--tts-rate", int(PARAMS.get("vitesse", 0)),
            "--output-tts-path", TMP + "/tts.wav", "--output-rvc-path", sortie] + reglages, "CONVERT")
else:
    applio(["infer", "--input-path", entree, "--output-path", sortie] + reglages, "CONVERT")

if not os.path.exists(sortie):
    echec("CONVERT", "Aucun fichier produit.")
vers_m4a(sortie, TRAVAIL + "/resultat.m4a")
etape("Terminé")
