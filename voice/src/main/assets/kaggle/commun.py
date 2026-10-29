# ---- Take my voice : outils communs (exécutés sur un GPU gratuit Kaggle) ----
# Le dictionnaire PARAMS est ajouté au début du script par l'application.
import glob
import json
import os
import shutil
import subprocess
import sys
import time

APPLIO_VERSION = "3.7.1"
TRAVAIL = "/kaggle/working"          # seul dossier renvoyé à l'application
TMP = "/kaggle/tmp"                  # tout le reste (non conservé)
APPLIO = TMP + "/applio"
PY = TMP + "/venv/bin/python"
DEBUT = time.time()

os.makedirs(TMP, exist_ok=True)
os.makedirs(TRAVAIL, exist_ok=True)
os.environ["APPLIO_LOGS_DIR"] = TMP + "/logs"
os.environ["PYTHONUNBUFFERED"] = "1"

AUDIO_EXT = (".wav", ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".webm",
             ".flac", ".3gp", ".amr", ".mp4", ".wma", ".mka")


def etape(nom):
    print(f"[TMV] {int(time.time() - DEBUT)}s : {nom}", flush=True)
    with open(TRAVAIL + "/etape.txt", "w") as f:
        f.write(nom)


def echec(code, detail=""):
    """Écrit une erreur lisible par l'application puis arrête le script."""
    print(f"[TMV] ERREUR {code} : {detail}", flush=True)
    with open(TRAVAIL + "/erreur.json", "w") as f:
        json.dump({"code": code, "detail": str(detail)[-1500:]}, f)
    sys.exit(1)


def sh(cmd, code="INSTALL", cwd=None):
    print("$ " + cmd, flush=True)
    r = subprocess.run(cmd, shell=True, cwd=cwd, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True)
    print(r.stdout[-4000:], flush=True)
    if r.returncode != 0:
        echec(code, r.stdout[-1500:])
    return r.stdout


def verifier_gpu():
    r = subprocess.run("nvidia-smi -L", shell=True, capture_output=True, text=True)
    if r.returncode != 0 or "GPU" not in r.stdout:
        echec("NO_GPU", "Aucun GPU. Vérifiez votre numéro de téléphone sur Kaggle.")


def installer_applio():
    etape("Installation d'Applio")
    subprocess.run("apt-get install -y -qq portaudio19-dev > /dev/null 2>&1", shell=True)
    sh("pip install -q uv")
    sh(f"git clone -q --depth 1 --branch {APPLIO_VERSION} https://github.com/IAHispano/Applio {APPLIO}")
    sh(f"uv venv {TMP}/venv --python 3.12 --seed -q")
    sh(f"uv pip install -q -r {APPLIO}/requirements-engine.txt "
       f"--extra-index-url https://download.pytorch.org/whl/cu128 "
       f"--index-strategy unsafe-best-match --python {PY}")
    etape("Téléchargement des modèles de base")
    sh(f"{PY} core.py prerequisites --models --exe --pretraineds-hifigan", cwd=APPLIO)
    if os.path.exists(APPLIO + "/assets/config_template.json"):
        shutil.copy(APPLIO + "/assets/config_template.json", APPLIO + "/assets/config.json")


def applio(args, code):
    """Lance une commande core.py d'Applio."""
    import shlex
    return sh(f"{PY} core.py " + " ".join(shlex.quote(str(a)) for a in args), code=code, cwd=APPLIO)


def vers_wav(src, dst, sr=40000):
    r = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-ac", "1",
                        "-ar", str(sr), dst], capture_output=True, text=True)
    return r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 2000


def vers_m4a(src, dst):
    sh(f"ffmpeg -y -loglevel error -i '{src}' -ac 1 -c:a aac -b:a 96k '{dst}'", code="EXPORT")


def duree(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "csv=p=0", path], capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return 0.0
