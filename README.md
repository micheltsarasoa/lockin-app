# LockIn — Parental Content Filter for Android

A bypass-resistant parental control app for Android that uses two layers of defense:
1. **Device Owner lockdown** — enforces always-on VPN via `DevicePolicyManager`, blocks safe-boot, disables ADB
2. **VPN-based content filter** — intercepts DNS queries and TLS SNI via a TUN interface, synthesizes NXDOMAIN responses for blocked domains

> See [`docs/roadmap.md`](docs/roadmap.md) for the full roadmap.
> See [`docs/testing.md`](docs/testing.md) for how to run the test suite.

---

## Prise en main rapide (Quick Start)

### 1. Prérequis

| Outil | Version minimale | Notes |
|-------|-----------------|-------|
| Android Studio | Hedgehog 2023.1.1+ | Recommandé — build, debug, déploiement intégrés |
| **ou** VS Code | any | Possible pour l'édition ; build en ligne de commande uniquement |
| JDK | 17 | Inclus dans Android Studio ; à installer manuellement pour VS Code |
| Android SDK | API 34 | Inclus dans Android Studio ; SDK Manager sinon |
| Git | any | |

> **Windows** : le SDK Android est généralement installé ici :
> `C:\Users\<votre-nom>\AppData\Local\Android\Sdk`

---

### 2. Cloner le dépôt

```bash
git clone https://github.com/micheltsarasoa/lockin-app.git
cd lockin-app
```

---

### 3. Créer `local.properties`

Android Studio crée ce fichier automatiquement à l'ouverture du projet.
Si vous travaillez en ligne de commande, créez-le manuellement à la racine du projet :

**Windows :**
```properties
# local.properties
sdk.dir=C\:\\Users\\jms\\AppData\\Local\\Android\\Sdk
```

> Note : les backslashes `\` doivent être échappés en `\\` sur Windows.

**macOS / Linux :**
```properties
# local.properties
sdk.dir=/Users/<votre-nom>/Library/Android/sdk
```

> `local.properties` est dans `.gitignore` — ne le commitez jamais.

---

### 4. Ouvrir le projet dans votre éditeur

#### Option A — Android Studio (recommandé)

1. Lancez Android Studio
2. **File → Open** → sélectionnez le dossier `lockin-app`
3. Attendez la fin du sync Gradle (barre de progression en bas)
4. Si Android Studio signale un JDK manquant : **File → Project Structure → SDK Location → JDK Location** → choisissez le JDK 17 embarqué (`<Android Studio>/jbr`)

#### Option B — VS Code

VS Code ne dispose pas d'un support Gradle/Android natif. Il peut servir pour éditer le code Kotlin, mais **le build et le déploiement se font obligatoirement en ligne de commande**.

1. Installez les extensions recommandées :
   - [Kotlin](https://marketplace.visualstudio.com/items?itemName=fwcd.kotlin) (`fwcd.kotlin`) — coloration syntaxique et autocomplétion basique
   - [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack) (`vscjava.vscode-java-pack`) — support JDK
   - [Gradle for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-gradle) (`vscjava.vscode-gradle`) — exécution des tâches Gradle depuis le panneau latéral

2. Assurez-vous que `JAVA_HOME` pointe sur un JDK 17 :
   ```bash
   # Windows (PowerShell)
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17..."
   java -version   # doit afficher 17

   # macOS / Linux
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   java -version
   ```

3. Ouvrez le dossier `lockin-app` dans VS Code (`File → Open Folder`)

4. Créez `local.properties` manuellement (voir étape 3 ci-dessus)

5. Utilisez le terminal intégré (`Ctrl+\`` `) pour toutes les commandes Gradle

> **Limitation VS Code :** l'autocomplétion Kotlin reste partielle sans le Language Server
> complet d'Android Studio. Pour une expérience optimale (refactoring, navigation,
> débogage sur device), Android Studio reste indispensable.

---

### 5. Builder le projet

**Via Android Studio :** `Build → Make Project` (`Ctrl+F9` / `⌘F9`)

**Via ligne de commande (Android Studio ou VS Code) :**
```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

L'APK de debug se trouve dans :
```
app/build/outputs/apk/debug/app-debug.apk
```

---

### 6. Lancer les tests unitaires

```bash
# Windows
gradlew.bat test

# macOS / Linux
./gradlew test
```

Rapport HTML :
```
<module>/build/reports/tests/testDebugUnitTest/index.html
```

Voir [`docs/testing.md`](docs/testing.md) pour le détail complet.

---

### 7. Installer l'APK sur un appareil

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

### 8. Configurer le Device Owner (obligatoire)

L'application nécessite d'être définie comme **Device Owner** pour activer toutes les
protections. Cette opération se fait **une seule fois**, avant de créer des comptes
utilisateurs sur l'appareil.

**Prérequis :** l'appareil doit n'avoir **aucun compte Google** configuré.

Connectez l'appareil via USB et exécutez :

```bash
adb shell dpm set-device-owner com.lockin.app/.admin.LockInDeviceAdminReceiver
```

Si la commande réussit :
```
Success: Device owner set to package com.lockin.app
```

> L'application affiche cette commande sur l'écran de configuration (Step 1) avec un
> bouton "Copy" pour faciliter la saisie.

---

### 9. Build de release (APK signé)

1. Copiez le template de keystore :
   ```bash
   cp keystore.properties.template keystore.properties
   ```

2. Générez un keystore :
   ```bash
   keytool -genkeypair -v \
     -keystore lockin-release.jks \
     -alias lockin \
     -keyalg RSA -keysize 4096 \
     -validity 10000
   ```

3. Renseignez `keystore.properties` avec vos valeurs.

4. Buildez le release :
   ```bash
   ./gradlew assembleRelease
   ```

> `keystore.properties` et `*.jks` sont dans `.gitignore` — ne les commitez jamais.

---

## Structure du projet

```
lockin-app/
├── app/                    # Module principal (UI, manifest, navigation)
├── core/
│   ├── vpn/                # VpnService + parseurs de paquets (DNS, TLS, HTTP)
│   ├── filter/             # Bloom filter + Room/SQLCipher + moteur de filtrage
│   ├── security/           # Argon2id PIN, brute-force guard, EncryptedPrefs
│   ├── admin/              # DevicePolicyManager, BootReceiver
│   ├── accessibility/      # AccessibilityService watchdog
│   └── sync/               # WorkManager, téléchargement des blocklists
├── test-support/           # Fakes réutilisables pour les tests
├── docs/
│   ├── roadmap.md          # Roadmap (Scope 1 → Compose en Scope 2)
│   └── testing.md          # Guide complet des tests
├── gradle/
│   └── libs.versions.toml  # Catalogue de versions Gradle
├── keystore.properties.template
└── local.properties        # ← À créer localement (voir étape 3)
```

---

## Problèmes fréquents

| Problème | Solution |
|----------|----------|
| `SDK location not found` | Créez `local.properties` avec `sdk.dir=...` (voir étape 3) |
| `Gradle sync failed` | Vérifiez la connexion internet (téléchargement Gradle 8.7) |
| `dpm set-device-owner` échoue | Supprimez tous les comptes Google de l'appareil d'abord |
| `INSTALL_FAILED_USER_RESTRICTED` | Activez "Sources inconnues" dans les paramètres de l'appareil |
| Build échoue sur `ksp` | Assurez-vous d'utiliser JDK 17 (pas 11 ni 21) |
| VS Code : `JAVA_HOME` non trouvé | Définissez `JAVA_HOME` vers un JDK 17 (voir étape 4B) |
| VS Code : tâches Gradle invisibles | Installez l'extension `vscjava.vscode-gradle` et rechargez la fenêtre |
| `ClassNotFoundException: GradleWrapperMain` | Le fichier `gradle/wrapper/gradle-wrapper.jar` est manquant. Exécutez `gradle wrapper --gradle-version 8.7` (si Gradle est installé), ou téléchargez-le manuellement : `mkdir -p gradle/wrapper && curl -L "https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar" -o gradle/wrapper/gradle-wrapper.jar` |
| `sed: character class syntax is [[:space:]]` + `GradleWrapperMain` | Même cause que ci-dessus — `gradle-wrapper.jar` absent. Voir solution précédente. |
