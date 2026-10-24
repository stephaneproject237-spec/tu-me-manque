package com.tumemanque.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Tout ce qui parle à Firebase. Les actions sensibles passent par des Cloud Functions. */
object Backend {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val functions get() = FirebaseFunctions.getInstance("europe-west1")

    suspend fun ensureSignedIn(): String {
        auth.currentUser?.uid?.let { return it }
        return auth.signInAnonymously().await().user!!.uid
    }

    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val result = functions.getHttpsCallable(name).call(data).await()
        @Suppress("UNCHECKED_CAST")
        return (result.data as? Map<String, Any?>) ?: emptyMap()
    }

    private suspend fun status(name: String, data: Map<String, Any?> = emptyMap()): String =
        (call(name, data)["status"] as? String) ?: "error"

    suspend fun saveToken() {
        val token = FirebaseMessaging.getInstance().token.await()
        saveToken(token)
    }

    suspend fun saveToken(token: String) {
        call("saveToken", mapOf("token" to token))
    }

    suspend fun claimUsername(username: String): String =
        status("claimUsername", mapOf("username" to username))

    suspend fun sendRequest(username: String): String =
        status("sendRequest", mapOf("username" to username))

    suspend fun respondRequest(requestId: String, accept: Boolean): String =
        status("respondRequest", mapOf("requestId" to requestId, "accept" to accept))

    suspend fun sendMiss(reply: Boolean): String =
        status("sendMiss", mapOf("reply" to reply))

    fun userFlow(uid: String): Flow<DocumentSnapshot?> = callbackFlow {
        val registration = db.document("users/$uid").addSnapshotListener { snapshot, error ->
            if (error == null) trySend(snapshot)
        }
        awaitClose { registration.remove() }
    }

    fun requestsFlow(field: String, uid: String): Flow<List<DocumentSnapshot>> = callbackFlow {
        val registration = db.collection("requests").whereEqualTo(field, uid)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) trySend(snapshot.documents)
            }
        awaitClose { registration.remove() }
    }
}
