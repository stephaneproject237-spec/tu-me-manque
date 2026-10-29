# ---- Take my voice : entraînement d'une voix ----
MODELE = "voix"
EPOQUES = int(PARAMS.get("epoques", 300))
SR = 40000

verifier_gpu()

# 1. Récupérer les enregistrements envoyés par l'application
etape("Préparation des enregistrements")
dossier = TMP + "/dataset"
os.makedirs(dossier, exist_ok=True)
sources = [p for p in glob.glob("/kaggle/input/**/*", recursive=True)
           if os.path.isfile(p) and p.lower().endswith(AUDIO_EXT)]

def isoler_voix(fichiers):
    """Passages YouTube : on retire musique et bruits avec Demucs (sur le GPU). En cas d'échec, on garde l'original."""
    if not fichiers:
        return {}
    etape("Séparation de la voix et de la musique (YouTube)")
    dm = TMP + "/demucs"
    try:
        sh("pip install -q uv")
        sh(f"uv venv {dm} --python 3.11 --seed -q")
        sh(f"uv pip install -q --python {dm}/bin/python demucs==4.0.1 torch==2.0.1 torchaudio==2.0.2 "
           f"numpy==1.26.4 soundfile --extra-index-url https://download.pytorch.org/whl/cu118 "
           f"--index-strategy unsafe-best-match")
        sortie = TMP + "/separe"
        listes = " ".join(f"'{f}'" for f in fichiers)
        sh(f"{dm}/bin/python -m demucs --two-stems vocals -n htdemucs -d cuda -o {sortie} {listes}")
        res = {}
        for f in fichiers:
            nom = os.path.splitext(os.path.basename(f))[0]
            v = glob.glob(f"{sortie}/htdemucs/{nom}/vocals.*")
            if v:
                res[f] = v[0]
        return res
    except SystemExit:
        if os.path.exists(TRAVAIL + "/erreur.json"):
            os.remove(TRAVAIL + "/erreur.json")
        print("[TMV] Séparation impossible, on garde l'audio d'origine.", flush=True)
        return {}


# Les extraits YouTube sont d'abord convertis en WAV, puis nettoyés
yt = []
for p in sources:
    if "_yt" in os.path.basename(p):
        w = f"{TMP}/yt_{len(yt):03d}.wav"
        if vers_wav(p, w, 44100):
            yt.append(w)
nettoyes = isoler_voix(yt)

total = 0.0
entrees = [p for p in sources if "_yt" not in os.path.basename(p)] + [nettoyes.get(w, w) for w in yt]
for i, p in enumerate(sorted(entrees)):
    dst = f"{dossier}/{i:03d}.wav"
    if vers_wav(p, dst, SR):
        total += duree(dst)
print(f"[TMV] {len(sources)} fichiers, {total / 60:.1f} minutes", flush=True)
if total < 60:
    echec("NO_AUDIO", f"Seulement {int(total)} secondes d'audio utilisable.")

# 2. Installer Applio
installer_applio()

# 3. Préparer, extraire, entraîner
etape("Découpage de la voix")
coeurs = str(min(os.cpu_count() or 2, 8))
applio(["preprocess", "--model-name", MODELE, "--dataset-path", dossier,
        "--sample-rate", SR, "--cpu-cores", coeurs, "--cut-preprocess", "Automatic",
        "--normalization-mode", "post"], "TRAIN")

etape("Analyse de la voix")
applio(["extract", "--model-name", MODELE, "--f0-method", "rmvpe", "--sample-rate", SR,
        "--cpu-cores", coeurs, "--gpu", "0", "--embedder-model", "contentvec"], "TRAIN")

etape(f"Entraînement ({EPOQUES} passages)")
applio(["train", "--model-name", MODELE, "--save-every-epoch", max(10, EPOQUES // 4),
        "--save-only-latest", "--total-epoch", EPOQUES, "--sample-rate", SR,
        "--batch-size", 8, "--gpu", "0", "--pretrained", "--vocoder", "HiFi-GAN",
        "--index-algorithm", "Auto"], "TRAIN")

# 4. Récupérer le modèle final
logs = os.environ["APPLIO_LOGS_DIR"] + "/" + MODELE
poids = sorted(glob.glob(f"{logs}/{MODELE}_*e_*s.pth"), key=os.path.getmtime)
index = glob.glob(f"{logs}/*.index")
if not poids:
    echec("TRAIN", "Le modèle n'a pas été créé.")
shutil.copy(poids[-1], TRAVAIL + "/model.pth")
if index:
    shutil.copy(index[0], TRAVAIL + "/model.index")
else:
    applio(["index", "--model-name", MODELE, "--index-algorithm", "Auto"], "TRAIN")
    index = glob.glob(f"{logs}/*.index")
    if not index:
        echec("TRAIN", "Le fichier d'index n'a pas été créé.")
    shutil.copy(index[0], TRAVAIL + "/model.index")

# 5. Un petit aperçu de la nouvelle voix
etape("Création d'un aperçu")
try:
    sortie = TMP + "/apercu.wav"
    applio(["tts", "--tts-file", "", "--tts-text", PARAMS.get("phrase", "Bonjour, voici ma voix."),
            "--tts-voice", PARAMS.get("voix_tts", "fr-FR-HenriNeural"),
            "--output-tts-path", TMP + "/apercu_tts.wav", "--output-rvc-path", sortie,
            "--pth-path", TRAVAIL + "/model.pth", "--index-path", TRAVAIL + "/model.index",
            "--pitch", int(PARAMS.get("hauteur", 0)), "--index-rate", 0.75], "APERCU")
    if os.path.exists(sortie):
        vers_m4a(sortie, TRAVAIL + "/apercu.m4a")
except SystemExit:
    if os.path.exists(TRAVAIL + "/erreur.json"):
        os.remove(TRAVAIL + "/erreur.json")   # l'aperçu est facultatif

with open(TRAVAIL + "/info.json", "w") as f:
    json.dump({"minutes": round(total / 60, 1), "epoques": EPOQUES,
               "duree_s": int(time.time() - DEBUT)}, f)
etape("Terminé")
