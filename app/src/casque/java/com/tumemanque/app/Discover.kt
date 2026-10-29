package com.tumemanque.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

data class DiscoAlbum(val id: Long, val name: String, val art: String, val year: String)
data class DiscoTrack(val n: Int, val title: String, val ms: Long)
/** Une suggestion pendant la frappe : le texte cherché, ce qu'on affiche, et si c'est un artiste. */
data class Sug(val text: String, val label: String, val sub: String = "", val artist: Boolean = false)

data class DiscoGenre(val emoji: String, val name: String, val query: String)

/** Ce que la personne cherche : un artiste (→ ses albums) ou un thème (→ des rubriques adaptées). */
sealed class DiscoKind {
    data class Artist(val id: Long, val name: String) : DiscoKind()
    data class Topic(val title: String, val rubrics: List<DiscoGenre>) : DiscoKind()
}

object Disco {
    private fun r(subject: String, emoji: String, name: String, q: String) =
        DiscoGenre(emoji, name, "$subject $q".replace(Regex("\\s+"), " ").trim())

    private fun films(): List<DiscoGenre> = listOf(
        "💥" to "Action", "🧭" to "Aventure", "🎨" to "Animation", "😂" to "Comédie", "🎭" to "Drame", "👻" to "Horreur",
        "🚀" to "Science-fiction", "🕵️" to "Thriller", "💕" to "Romance", "🔍" to "Policier", "🧙" to "Fantastique",
        "⚔️" to "Guerre", "🤠" to "Western", "🎥" to "Documentaire", "🌍" to "Africain",
    ).map { (e, n) -> DiscoGenre(e, n, if (n == "Africain") "film africain nollywood complet" else "film ${n.lowercase()} complet en français") }

    private fun series(): List<DiscoGenre> = listOf(
        "💥" to "Action", "🔍" to "Policière", "🎭" to "Drame", "😂" to "Comédie", "🚀" to "Science-fiction", "👻" to "Horreur / Fantastique",
        "💕" to "Romance", "🏥" to "Médicale", "⚖️" to "Judiciaire", "🌍" to "Africaine", "📺" to "Télénovela", "🕰️" to "Historique",
    ).map { (e, n) -> DiscoGenre(e, n, "série ${n.lowercase()} épisodes complets en français") }

    private fun animeR(s: String) = listOf(
        r(s, "1️⃣", "Saison 1", "saison 1 VF"), r(s, "2️⃣", "Saison 2", "saison 2 VF"), r(s, "3️⃣", "Saison 3", "saison 3 VF"),
        r(s, "📺", "Épisodes complets", "épisodes complets VF"), r(s, "🎵", "Openings", "opening"), r(s, "🎶", "Endings", "ending"),
        r(s, "🔥", "Meilleurs moments", "meilleurs moments"), r(s, "🎬", "Films", "film complet"), r(s, "🎼", "Musiques (OST)", "OST soundtrack"),
        r(s, "📝", "Résumé", "résumé histoire"), r(s, "⚔️", "Meilleurs combats", "meilleurs combats"),
    )

    private fun seriesR(s: String) = listOf(
        r(s, "1️⃣", "Saison 1", "saison 1 épisodes"), r(s, "2️⃣", "Saison 2", "saison 2 épisodes"), r(s, "3️⃣", "Saison 3", "saison 3 épisodes"),
        r(s, "🎞️", "Bande-annonce", "bande annonce"), r(s, "📺", "Épisodes complets", "épisodes complets VF"), r(s, "🔥", "Scènes cultes", "scènes cultes"),
        r(s, "🎵", "Générique & musiques", "générique OST"), r(s, "🎤", "Interviews du casting", "interview casting"), r(s, "📝", "Résumé", "résumé"),
    )

    private fun movieR(s: String) = listOf(
        r(s, "🎞️", "Bande-annonce", "bande annonce"), r(s, "🍿", "Film complet", "film complet VF"), r(s, "🔥", "Scènes cultes", "scènes cultes"),
        r(s, "🎬", "Making of", "making of coulisses"), r(s, "🎼", "Bande originale", "bande originale OST"), r(s, "🎤", "Interviews", "interview acteurs"),
        r(s, "📝", "Critique", "critique avis"), r(s, "🎥", "Suites & films similaires", "suite films similaires"),
    )

    private fun artistR(a: String) = listOf(
        r(a, "🎬", "Clips officiels", "clip officiel"), r(a, "🔥", "Meilleurs titres", "meilleures chansons hits"), r(a, "🎤", "Concerts live", "concert live"),
        r(a, "🎙️", "Interviews", "interview"), r(a, "🔁", "Remixes", "remix"), r(a, "🎧", "Playlist / Mix", "mix playlist"), r(a, "🤝", "Duos & featurings", "feat duo"),
    )

    private fun docR(s: String): List<DiscoGenre> = listOf(
        "🏛️" to "Histoire", "🌿" to "Nature", "🐾" to "Animaux", "🔬" to "Science", "🚀" to "Espace", "🕵️" to "Crime & enquêtes",
        "🌍" to "Société", "⚔️" to "Guerre", "✈️" to "Voyage", "💰" to "Économie", "🏥" to "Santé", "🎨" to "Culture & art", "🧠" to "Mystères",
    ).map { (e, n) -> DiscoGenre(e, n, "documentaire $n $s complet".replace(Regex("\\s+"), " ").trim()) }

    private fun sportR(s: String) = listOf(
        r(s, "⚽", "Buts", "meilleurs buts"), r(s, "📺", "Résumés de matchs", "résumé match"), r(s, "🔥", "Meilleurs moments", "meilleurs moments"),
        r(s, "🎯", "Dribbles & gestes", "dribbles skills"), r(s, "🎤", "Interviews", "interview"), r(s, "🎬", "Documentaires", "documentaire"),
        r(s, "🏆", "Finales & grands matchs", "finale match complet"), r(s, "📰", "Actualités", "actualité aujourd'hui"),
    )

    private fun foodR(s: String) = listOf(
        r(s, "🥗", "Entrées", "recette entrée"), r(s, "🍲", "Plats", "recette plat"), r(s, "🍰", "Desserts", "recette dessert"),
        r(s, "⚡", "Rapide (15 min)", "recette rapide facile"), r(s, "🥐", "Pâtisserie", "recette pâtisserie"), r(s, "🌍", "Cuisine africaine", "recette cuisine africaine"),
        r(s, "🥬", "Végétarien", "recette végétarienne"), r(s, "👨‍🍳", "Astuces de chef", "astuces de chef"),
    )

    private fun gameR(s: String) = listOf(
        r(s, "🎮", "Gameplay", "gameplay"), r(s, "💡", "Astuces & conseils", "astuces conseils"), r(s, "📘", "Tutoriels", "tuto débutant"),
        r(s, "🔥", "Meilleurs moments", "meilleurs moments"), r(s, "🏁", "Speedrun", "speedrun"), r(s, "🆕", "Nouveautés", "nouveautés mise à jour"),
        r(s, "🏆", "Compétition / e-sport", "tournoi esport"), r(s, "🧩", "Le jeu en entier", "walkthrough complet"),
    )

    private fun humourR(s: String) = listOf(
        r(s, "🎭", "Sketchs", "sketch"), r(s, "🎤", "One-man-show", "one man show complet"), r(s, "😂", "Meilleurs moments", "meilleurs moments drôles"),
        r(s, "🎙️", "Interviews", "interview"), r(s, "🎬", "Spectacles complets", "spectacle complet"), r(s, "🌍", "Humour africain", "humour africain"),
    )

    private fun courseR(s: String) = listOf(
        r(s, "🌱", "Pour débutants", "pour débutants"), r(s, "🚀", "Niveau avancé", "niveau avancé"), r(s, "📚", "Cours complet", "cours complet"),
        r(s, "✍️", "Exercices", "exercices corrigés"), r(s, "💡", "Astuces", "astuces"), r(s, "🎓", "Formation gratuite", "formation gratuite"),
    )

    private fun talkR(s: String) = listOf(
        r(s, "🔥", "Épisodes populaires", "épisode populaire"), r(s, "🆕", "Les plus récents", "dernier épisode"), r(s, "🎙️", "Entretiens", "entretien complet"),
        r(s, "💡", "Moments forts", "moments forts"), r(s, "🌍", "Afrique", "afrique"),
    )

    private fun newsR(s: String) = listOf(
        r(s, "📰", "À la une", "à la une aujourd'hui"), r(s, "🌍", "Afrique", "afrique actualité"), r(s, "🏛️", "Politique", "politique"),
        r(s, "💰", "Économie", "économie"), r(s, "⚽", "Sport", "sport actualité"), r(s, "🎭", "Culture", "culture"), r(s, "🔬", "Science & tech", "science technologie"),
    )

    private fun musicR(s: String): List<DiscoGenre> = listOf(
        "🎤" to "Afrobeat", "🎶" to "Rumba congolaise", "🕺" to "Coupé-décalé", "🥁" to "Makossa", "🎸" to "Rock", "🎧" to "Rap / Hip-hop",
        "💖" to "R&B", "🙏" to "Gospel & louange", "🌴" to "Zouk", "🎷" to "Jazz", "🌿" to "Reggae", "🎹" to "Pop", "🔥" to "Amapiano", "🌍" to "Musique camerounaise",
    ).map { (e, n) -> DiscoGenre(e, n, "$n $s meilleures chansons".replace(Regex("\\s+"), " ").trim()) }

    private fun kidsR(s: String) = listOf(
        r(s, "🎵", "Comptines", "comptines"), r(s, "📺", "Épisodes complets", "épisodes complets"), r(s, "📖", "Histoires", "histoires pour enfants"),
        r(s, "🎨", "Apprendre en s'amusant", "apprendre enfants"), r(s, "🔤", "Alphabet & chiffres", "alphabet chiffres"), r(s, "🎬", "Films", "film complet"),
    )

    private fun genericR(s: String) = listOf(
        r(s, "🔥", "Les plus populaires", "les plus vus"), r(s, "🆕", "Les plus récents", "nouveau 2026"), r(s, "🎞️", "Version complète", "complet"),
        r(s, "⭐", "Meilleurs moments", "meilleurs moments"), r(s, "🎧", "Mix / Compilation", "compilation mix"), r(s, "🎤", "Live", "live"),
        r(s, "🎙️", "Interviews", "interview"), r(s, "📺", "Documentaires", "documentaire"),
    )

    private val animeNames = Regex(
        "\\b(naruto|one piece|dragon ball|bleach|death note|attack on titan|l'attaque des titans|my hero academia|demon slayer|jujutsu kaisen|hunter x hunter|fairy tail|" +
            "tokyo ghoul|fullmetal alchemist|sword art online|one punch man|black clover|boruto|pokemon|pokémon|digimon|yu-?gi-?oh|saint seiya|les chevaliers du zodiaque|" +
            "detective conan|conan|doraemon|shin chan|tom et jerry|tom and jerry|scooby-?doo|bob l'éponge|spongebob|naruto shippuden|gintama|haikyuu|blue lock|chainsaw man|" +
            "spy x family|dr\\.? stone|fire force|seven deadly sins|nanatsu|vinland saga|cowboy bebop|evangelion|code geass|mob psycho|ben ?10|totally spies|miraculous|ladybug|dora|peppa pig|pat patrouille|mickey|simpsons|les simpson|south park|family guy|rick and morty)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun has(t: String, p: String) = Regex("\\b($p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
    private fun strip(t: String, p: String) = t.replace(Regex("\\b($p)\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()

    private fun byKeywords(t: String): DiscoKind.Topic? {
        when {
            has(t, "films?|movies?|cin[eé]ma") && strip(t, "films?|movies?|cin[eé]ma|complets?|en fran[cç]ais|vf|streaming").length < 3 ->
                return DiscoKind.Topic("Films · choisis un genre", films())
            has(t, "s[eé]ries?") && strip(t, "s[eé]ries?|complet(e)?s?|en fran[cç]ais|vf|streaming").length < 3 ->
                return DiscoKind.Topic("Séries · choisis un genre", series())
            has(t, "documentaires?|docu|reportages?") -> { val s = strip(t, "documentaires?|docu|reportages?|complets?"); return DiscoKind.Topic(if (s.isBlank()) "Documentaires · choisis un thème" else "Documentaires · $s", docR(s)) }
            has(t, "animes?|anim[eé]s?|mangas?|dessins? anim[eé]s?|cartoons?") || animeNames.containsMatchIn(t) -> {
                val s = strip(t, "animes?|anim[eé]s?|mangas?|dessins? anim[eé]s?|cartoons?")
                return DiscoKind.Topic("Dessin animé · ${s.ifBlank { "rubriques" }}", if (s.isBlank()) animeGeneric() else animeR(s))
            }
            has(t, "recettes?|cuisine|cuisiner|p[aâ]tisserie|g[aâ]teaux?|plats?|cooking") -> { val s = strip(t, "recettes?|cuisine|cuisiner|cooking"); return DiscoKind.Topic("Cuisine · ${s.ifBlank { "idées" }}", foodR(s)) }
            has(t, "foot|football|basket|nba|ligue des champions|champions league|tennis|mma|ufc|boxe|rugby|cyclisme|athl[eé]tisme|premier league|liga|ligue 1|serie a|mondial|coupe du monde|lions indomptables|sport") ->
                return DiscoKind.Topic("Sport · $t", sportR(t))
            has(t, "gameplay|jeux? vid[eé]o|fortnite|minecraft|fifa|ea fc|efootball|gta|pubg|free fire|call of duty|cod|roblox|warzone|valorant|league of legends|clash of clans|among us|gaming|let'?s play") ->
                return DiscoKind.Topic("Jeu vidéo · $t", gameR(t))
            has(t, "humour|sketchs?|stand-?up|one[- ]?man[- ]?show|com[eé]dien(ne)?|blagues?|dr[oô]le|rire") -> { val s = strip(t, "humour|sketchs?|stand-?up|blagues?"); return DiscoKind.Topic("Humour · ${s.ifBlank { "rubriques" }}", humourR(s)) }
            has(t, "tutos?|tutoriels?|cours|apprendre|formation|leçons?|lecons?|exercices?") -> return DiscoKind.Topic("Apprendre · $t", courseR(t))
            has(t, "podcasts?|interviews?|d[eé]bats?|conf[eé]rences?|talk") -> { val s = strip(t, "podcasts?|interviews?|d[eé]bats?|conf[eé]rences?"); return DiscoKind.Topic("Podcasts & entretiens · ${s.ifBlank { "rubriques" }}", talkR(s)) }
            has(t, "actualit[eé]s?|news|infos?|journal|jt") -> return DiscoKind.Topic("Actualité · $t", newsR(strip(t, "actualit[eé]s?|news|infos?|journal|jt")))
            has(t, "comptines?|b[eé]b[eé]|enfants?|kids") -> return DiscoKind.Topic("Enfants · $t", kidsR(strip(t, "comptines?|enfants?|kids")))
            has(t, "musiques?|chansons?|playlist|mix|clips?|hits?") -> return DiscoKind.Topic("Musique · choisis un style", musicR(strip(t, "musiques?|chansons?|playlist|mix|clips?|hits?")))
        }
        return null
    }

    private fun animeGeneric() = listOf(
        "⚔️" to "Shonen", "💕" to "Romance", "🧙" to "Fantastique", "🚀" to "Science-fiction", "😂" to "Comédie", "👻" to "Horreur", "🏀" to "Sport", "🎵" to "Openings", "🎶" to "Endings", "🧒" to "Pour enfants",
    ).map { (e, n) -> DiscoGenre(e, n, "anime ${n.lowercase()} VF épisodes") }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun get(url: String): JSONObject? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
        if (c.responseCode != 200) null else JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Détecte le thème de la recherche : un artiste (→ albums) ou un domaine (→ rubriques). Il y a toujours des rubriques. */
    suspend fun detect(q: String): DiscoKind = withContext(Dispatchers.IO) {
        val t = q.trim()
        val nq = norm(t)
        byKeywords(t)?.let { return@withContext it }
        if (nq.length >= 3) {
            val a = get("https://itunes.apple.com/search?term=${enc(t)}&entity=musicArtist&limit=5")?.optJSONArray("results")
            if (a != null) {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val name = o.optString("artistName")
                    val nn = norm(name)
                    val ok = nn == nq || (nq.length >= 5 && lev(nn, nq) <= 1) || (nq.length >= 4 && nn.startsWith(nq) && nn.length - nq.length <= 3)
                    if (ok && o.optLong("artistId") > 0) return@withContext DiscoKind.Artist(o.optLong("artistId"), name)
                }
            }
            val tv = get("https://itunes.apple.com/search?term=${enc(t)}&entity=tvShow&limit=4")?.optJSONArray("results")
            if (tv != null) for (i in 0 until tv.length()) {
                val o = tv.getJSONObject(i)
                val nm = o.optString("artistName").ifBlank { o.optString("collectionName") }
                if (norm(nm).let { it == nq || (nq.length >= 4 && it.startsWith(nq)) }) {
                    val g = o.optString("primaryGenreName")
                    return@withContext if (Regex("anim|kids|enfant|famil", RegexOption.IGNORE_CASE).containsMatchIn(g)) DiscoKind.Topic("Dessin animé · $t", animeR(t))
                    else DiscoKind.Topic("Série · $t", seriesR(t))
                }
            }
            val m = get("https://itunes.apple.com/search?term=${enc(t)}&entity=movie&limit=3")?.optJSONArray("results")
            if (m != null) for (i in 0 until m.length()) if (norm(m.getJSONObject(i).optString("trackName")) == nq) return@withContext DiscoKind.Topic("Film · $t", movieR(t))
        }
        DiscoKind.Topic("Plus d'idées · $t", genericR(t))
    }

    fun artistRubrics(name: String) = artistR(name)

    /** Pendant la frappe : l'artiste le plus proche d'abord, puis les titres qui y ressemblent, puis les suggestions YouTube. */
    suspend fun suggest(q: String): List<Sug> = coroutineScope {
        val t = q.trim()
        val nq = norm(t)
        val art = async(Dispatchers.IO) { get("https://itunes.apple.com/search?term=${enc(t)}&entity=musicArtist&limit=6") }
        val songs = async(Dispatchers.IO) { get("https://itunes.apple.com/search?term=${enc(t)}&entity=song&limit=12") }
        val yt = async { runCatching { Yt.suggest(t) }.getOrDefault(emptyList()) }
        val out = ArrayList<Sug>()
        val seen = HashSet<String>()
        // L'artiste dont le nom ressemble le plus à ce qui est tapé.
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        art.await()?.optJSONArray("results")?.let { a ->
            for (i in 0 until a.length()) {
                val name = a.getJSONObject(i).optString("artistName")
                val nn = norm(name)
                if (nn.isEmpty() || nq.isEmpty()) continue
                val score = when {
                    nn == nq -> 0
                    nn.startsWith(nq) -> 1 + (nn.length - nq.length)
                    nn.contains(nq) && nq.length >= 3 -> 40 + (nn.length - nq.length)
                    lev(nn, nq) <= maxOf(2, nq.length / 3) -> 100 + lev(nn, nq)
                    else -> Int.MAX_VALUE
                }
                if (score < bestScore) { bestScore = score; best = name }
            }
        }
        best?.let { if (seen.add(norm(it))) out.add(Sug(it, "🎤  $it", "Artiste", true)) }
        songs.await()?.optJSONArray("results")?.let { a ->
            var n = 0
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("wrapperType") != "track") continue
                val title = o.optString("trackName"); val artist = o.optString("artistName")
                if (title.isBlank() || !seen.add(norm("$artist $title"))) continue
                out.add(Sug("$artist $title", title, artist))
                if (++n >= 6) break
            }
        }
        for (y in yt.await()) { if (out.size >= 10) break; if (seen.add(norm(y))) out.add(Sug(y, y)) }
        out
    }

    suspend fun albums(artistId: Long): List<DiscoAlbum> = withContext(Dispatchers.IO) {
        val r = get("https://itunes.apple.com/lookup?id=$artistId&entity=album&limit=80")?.optJSONArray("results") ?: return@withContext emptyList()
        val out = ArrayList<Triple<String, String, DiscoAlbum>>()
        val seen = HashSet<String>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            if (o.optString("wrapperType") != "collection") continue
            val name = o.optString("collectionName")
            if (name.isBlank() || name.endsWith(" - Single")) continue
            val key = norm(name.replace(Regex("\\(.*?\\)|\\[.*?]"), ""))
            if (!seen.add(key)) continue
            out.add(Triple(o.optString("releaseDate"), name, DiscoAlbum(o.optLong("collectionId"), name, o.optString("artworkUrl100").replace("100x100", "400x400"), o.optString("releaseDate").take(4))))
        }
        out.sortedByDescending { it.first }.map { it.third }
    }

    suspend fun tracks(albumId: Long): List<DiscoTrack> = withContext(Dispatchers.IO) {
        val r = get("https://itunes.apple.com/lookup?id=$albumId&entity=song&limit=200")?.optJSONArray("results") ?: return@withContext emptyList()
        val l = ArrayList<Triple<Int, Int, DiscoTrack>>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            if (o.optString("wrapperType") != "track") continue
            l.add(Triple(o.optInt("discNumber", 1), o.optInt("trackNumber", i), DiscoTrack(o.optInt("trackNumber", i), o.optString("trackName"), o.optLong("trackTimeMillis"))))
        }
        l.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}

/** Le bandeau en verre au-dessus des résultats : albums (artiste) et rubriques du thème, à faire défiler. Toujours affiché. */
@Composable
fun DiscoverCard(
    kind: DiscoKind, albums: List<DiscoAlbum>?, selAlbum: Long?, selGenre: String?,
    onClose: () -> Unit, onAlbum: (DiscoAlbum) -> Unit, onGenre: (DiscoGenre) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).glass(RoundedCornerShape(24.dp), 0.10f).padding(vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (kind is DiscoKind.Artist) "🎵  Albums · ${kind.name}" else (kind as DiscoKind.Topic).title,
                color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            RoundBtn(Ic.Close, { onClose() }, size = 32.dp, iconSize = 16.dp, fill = Color(0x22FFFFFF))
        }
        Spacer(Modifier.height(10.dp))
        if (kind is DiscoKind.Artist) {
            when {
                albums == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = P.blueSoft)
                }
                albums.isEmpty() -> Text("Aucun album trouvé pour cet artiste.", color = P.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                else -> LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.id }) { a ->
                        val on = selAlbum == a.id
                        Column(Modifier.width(112.dp).pressable { onAlbum(a) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(112.dp).clip(RoundedCornerShape(18.dp)).background(P.card2)
                                    .then(if (on) Modifier.glass(RoundedCornerShape(18.dp), 0.0f, P.blueSoft) else Modifier),
                            ) {
                                AsyncImage(model = a.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
                                if (on) Box(Modifier.fillMaxSize().background(Color(0x442F7BFF)))
                            }
                            Text(a.name, color = if (on) P.blueSoft else P.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                            if (a.year.isNotBlank()) Text(a.year, color = P.sub, fontSize = 11.sp)
                        }
                    }
                }
            }
            Text("Et aussi", color = P.sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp))
            RubricRow(Disco.artistRubrics(kind.name), selGenre, onGenre)
        } else {
            RubricRow((kind as DiscoKind.Topic).rubrics, selGenre, onGenre)
        }
    }
}

@Composable
private fun RubricRow(list: List<DiscoGenre>, selGenre: String?, onGenre: (DiscoGenre) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.name }) { g ->
            val on = selGenre == g.name
            Column(Modifier.width(78.dp).pressable { onGenre(g) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(68.dp).glass(CircleShape, if (on) 0.22f else 0.08f, if (on) P.blueSoft else Color.White),
                    contentAlignment = Alignment.Center,
                ) { Text(g.emoji, fontSize = 30.sp) }
                Text(g.name, color = if (on) P.blueSoft else P.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Une ligne de titre d'un album. */
@Composable
fun TrackRow(t: DiscoTrack, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${t.n}", color = P.sub, fontSize = 14.sp, modifier = Modifier.width(30.dp))
        Text(t.title, color = P.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (t.ms > 0) Text(fmt(t.ms), color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.width(10.dp))
        CIcon(Ic.Play, P.blueSoft, 20.dp)
    }
}
