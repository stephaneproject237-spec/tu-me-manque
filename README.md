# Tu me manques 💗

Application Android pour couples : on choisit un identifiant, on ajoute son partenaire, et un bouton envoie « Tu me manques » (réponse possible : « Tu me manques aussi »).

- `app/` : application Android (Kotlin, Jetpack Compose)
- `supabase/` : base de données (`schema.sql`) et fonction d'envoi des notifications (`notify/index.ts`)
- `.github/workflows/build.yml` : compile l'APK automatiquement et le publie dans « Releases »
