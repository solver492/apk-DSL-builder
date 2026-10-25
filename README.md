# OpenCode Mobile — structure du projet

```
OpenCodeMobile/
├── settings.gradle.kts            (include(":app") + repos google/mavenCentral)
├── build.gradle.kts               (plugins AGP 8.5+, Kotlin 2.0+, compose compiler plugin)
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/digitalsolverland/opencode/
        │   ├── MainActivity.kt
        │   ├── OpenCodeApp.kt        (Dashboard + Settings)
        │   ├── OpenCodeService.kt    (Foreground Service)
        │   ├── AgentBus.kt           (logs + statut partagés)
        │   ├── SecureSettings.kt     (EncryptedSharedPreferences)
        │   └── BootReceiver.kt
        ├── jniLibs/arm64-v8a/
        │   └── libnode.so            <- binaire Node.js arm64 renommé (voir ci-dessous)
        └── assets/opencode/
            ├── cli.js                <- ton entrée JS (bundle OpenCode / wrapper)
            └── node_modules/ ...     <- dépendances si besoin
```

## Où placer le binaire et les scripts

**Binaire Node (`libnode.so`)** : Android 10+ interdit d'exécuter un fichier écrit dans
`filesDir` (règle W^X). Le seul chemin fiable : l'embarquer comme *lib native*.
1. Récupère un build Node.js **arm64 pour Android** (ex. depuis Termux : `$PREFIX/bin/node`,
   ou un build `nodejs-mobile`/cross-compilé NDK). Il doit être lié à bionic, pas glibc.
2. Renomme-le `libnode.so` et place-le dans `app/src/main/jniLibs/arm64-v8a/`.
3. `useLegacyPackaging = true` + `extractNativeLibs="true"` (déjà configurés) l'extraient dans
   `applicationInfo.nativeLibraryDir`, d'où le service l'exécute.
   Si ton Node dépend de libs partagées (libc++_shared, libssl…), ajoute-les aussi en `lib*.so`
   dans le même dossier.

**Scripts / OpenCode** : mets-les dans `app/src/main/assets/opencode/`. Au premier lancement
(et à chaque nouvelle `versionCode`), le service les copie dans `filesDir/opencode/`.
Le point d'entrée par défaut est `opencode/cli.js`, modifiable dans Paramètres.

## Points à connaître

- **Commande lancée** : `libnode.so opencode/cli.js serve --hostname 127.0.0.1 --port <PORT>`
  (modifiable dans Paramètres, `{PORT}` est substitué).
- **Envoi de prompts** : écrits sur le **stdin** du processus. Si ton agent expose une API HTTP
  (mode `serve`), remplace `sendToAgent()` par un POST vers `http://127.0.0.1:<PORT>/...`.
  Le bouton « Vérifier le statut » fait déjà un GET de santé sur la boucle locale.
- **Clés API** : stockées chiffrées, injectées en variables d'environnement
  (`OPENAI_API_KEY`, `OPENROUTER_API_KEY`, `ANTHROPIC_API_KEY`) au démarrage du process.
- **Persistance** : `START_STICKY`, WakeLock partiel, redémarrage auto (5 essais), démarrage au boot optionnel.
  Sur certains constructeurs (Xiaomi, Huawei…), désactive l'optimisation batterie pour l'app.
- **Binaire OpenCode natif** : l'officiel est un exécutable Bun compilé (non-Android). Il faut
  soit la version npm/JS exécutée par Node, soit un build compatible bionic.
- Les actions rapides se rechargent au redémarrage de l'app après modification.
