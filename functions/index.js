const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
setGlobalOptions({ region: "europe-west1", maxInstances: 10 });

const db = admin.firestore();
const { FieldValue } = admin.firestore;
const NAME_RE = /^[a-z0-9_]{3,20}$/;

function requireAuth(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Connexion requise");
  return request.auth.uid;
}

function cleanName(value) {
  return String(value || "").trim().replace(/^@/, "").toLowerCase();
}

async function notify(uid, title, body, data = {}) {
  const snap = await db.doc(`users/${uid}`).get();
  const token = snap.exists ? snap.data().fcmToken : null;
  if (!token) return;
  try {
    await admin.messaging().send({
      token,
      notification: { title, body },
      data,
      android: {
        priority: "high",
        notification: { channelId: "miss", sound: "default" },
      },
    });
  } catch (err) {
    const code = err && err.code;
    if (
      code === "messaging/registration-token-not-registered" ||
      code === "messaging/invalid-registration-token"
    ) {
      await db.doc(`users/${uid}`).update({ fcmToken: FieldValue.delete() });
    } else {
      console.error("Notification impossible", err);
    }
  }
}

/** Enregistre le jeton de notification de cet appareil. */
exports.saveToken = onCall(async (request) => {
  const uid = requireAuth(request);
  const token = String((request.data && request.data.token) || "");
  if (!token) throw new HttpsError("invalid-argument", "token manquant");
  await db.doc(`users/${uid}`).set({ fcmToken: token }, { merge: true });
  return { status: "ok" };
});

/** Réserve un identifiant unique pour cette personne. */
exports.claimUsername = onCall(async (request) => {
  const uid = requireAuth(request);
  const name = cleanName(request.data && request.data.username);
  if (!NAME_RE.test(name)) return { status: "invalid" };

  const userRef = db.doc(`users/${uid}`);
  const nameRef = db.doc(`usernames/${name}`);
  return db.runTransaction(async (tx) => {
    const [user, existing] = await Promise.all([tx.get(userRef), tx.get(nameRef)]);
    if (user.exists && user.data().username) return { status: "has_username" };
    if (existing.exists) return { status: "taken" };
    tx.set(nameRef, { uid });
    tx.set(userRef, { username: name, createdAt: FieldValue.serverTimestamp() }, { merge: true });
    return { status: "ok" };
  });
});

async function link(uidA, nameA, uidB, nameB) {
  await db.runTransaction(async (tx) => {
    const refA = db.doc(`users/${uidA}`);
    const refB = db.doc(`users/${uidB}`);
    const [a, b] = await Promise.all([tx.get(refA), tx.get(refB)]);
    if (a.data().partnerUid || b.data().partnerUid) throw new HttpsError("failed-precondition", "partner_busy");
    tx.update(refA, { partnerUid: uidB, partnerName: nameB });
    tx.update(refB, { partnerUid: uidA, partnerName: nameA });
  });
  // Nettoyer toutes les demandes restantes de ces deux personnes.
  const stale = [];
  for (const uid of [uidA, uidB]) {
    for (const field of ["fromUid", "toUid"]) {
      const snap = await db.collection("requests").where(field, "==", uid).get();
      snap.forEach((d) => stale.push(d.ref));
    }
  }
  await Promise.all(stale.map((ref) => ref.delete()));
}

/** Envoie une demande de partenaire à un identifiant. */
exports.sendRequest = onCall(async (request) => {
  const uid = requireAuth(request);
  const target = cleanName(request.data && request.data.username);
  if (!NAME_RE.test(target)) return { status: "invalid" };

  const meSnap = await db.doc(`users/${uid}`).get();
  const me = meSnap.exists ? meSnap.data() : null;
  if (!me || !me.username) throw new HttpsError("failed-precondition", "Choisis d'abord un identifiant");
  if (me.partnerUid) return { status: "already_has_partner" };
  if (me.username === target) return { status: "self" };

  const nameSnap = await db.doc(`usernames/${target}`).get();
  if (!nameSnap.exists) return { status: "not_found" };
  const toUid = nameSnap.data().uid;

  const toSnap = await db.doc(`users/${toUid}`).get();
  if (toSnap.data().partnerUid) return { status: "partner_busy" };

  // Si l'autre personne m'a déjà demandé, on relie directement les deux.
  const reverse = await db.doc(`requests/${toUid}_${uid}`).get();
  if (reverse.exists) {
    try {
      await link(uid, me.username, toUid, target);
    } catch (err) {
      return { status: "partner_busy" };
    }
    await notify(toUid, "Vous êtes partenaires 💗", `@${me.username} et toi êtes maintenant partenaires`, { type: "linked" });
    return { status: "linked" };
  }

  const id = `${uid}_${toUid}`;
  const existing = await db.doc(`requests/${id}`).get();
  if (existing.exists) return { status: "already_sent" };

  await db.doc(`requests/${id}`).set({
    fromUid: uid,
    fromName: me.username,
    toUid,
    toName: target,
    createdAt: FieldValue.serverTimestamp(),
  });
  await notify(toUid, "Nouvelle demande 💗", `@${me.username} veut être ton partenaire`, { type: "request" });
  return { status: "sent" };
});

/** Accepte ou refuse une demande reçue. */
exports.respondRequest = onCall(async (request) => {
  const uid = requireAuth(request);
  const requestId = String((request.data && request.data.requestId) || "");
  const accept = !!(request.data && request.data.accept);
  if (!requestId) return { status: "not_found" };

  const ref = db.doc(`requests/${requestId}`);
  const snap = await ref.get();
  if (!snap.exists || snap.data().toUid !== uid) return { status: "not_found" };
  const req = snap.data();

  if (!accept) {
    await ref.delete();
    return { status: "ok" };
  }

  try {
    await link(req.fromUid, req.fromName, req.toUid, req.toName);
  } catch (err) {
    await ref.delete();
    return { status: "partner_busy" };
  }
  await notify(req.fromUid, "Demande acceptée 💗", `@${req.toName} a accepté d'être ton partenaire`, { type: "linked" });
  return { status: "ok" };
});

/** Envoie « Tu me manques » (ou la réponse « Tu me manques aussi ») au partenaire. */
exports.sendMiss = onCall(async (request) => {
  const uid = requireAuth(request);
  const reply = !!(request.data && request.data.reply);

  const meSnap = await db.doc(`users/${uid}`).get();
  const me = meSnap.exists ? meSnap.data() : null;
  if (!me || !me.partnerUid) return { status: "no_partner" };

  const body = reply ? "Tu me manques aussi 💗" : "Tu me manques 💗";
  await db.doc(`users/${me.partnerUid}`).update({
    lastMiss: { from: me.username, reply, at: FieldValue.serverTimestamp() },
  });
  if (reply) {
    // Ma propre demande est traitée : on l'efface de mon écran.
    await db.doc(`users/${uid}`).update({ lastMiss: FieldValue.delete() });
  }
  await notify(me.partnerUid, `@${me.username}`, body, { type: reply ? "reply" : "miss" });
  return { status: "ok" };
});
