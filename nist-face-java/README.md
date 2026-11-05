# PNG de visage vers ANSI/NIST-ITL 1-2011 Update 2015 — Java 17

Projet complet, sans dépendance Java externe, pour produire une transaction en **encodage traditionnel** contenant exactement **un type 1, un type 2 et un type 10 FACE**. Le code de version est `0502`. L'image est embarquée en binaire dans `10.999`, et non en Base64. L'extension `.an2` est un choix de nommage ; elle ne réalise aucune conversion à elle seule.

**Portée :** implémentation d'un sous-ensemble explicite de la norme, avec PNG et `SAP=0` (profil d'acquisition inconnu). Ce n'est pas un SDK couvrant tous les champs et profils. Les tests réussis ne constituent pas une certification NIST, ni une garantie d'acceptation par votre destinataire.

## Démarrage

Prérequis : **JDK 17 ou ultérieur** avec `java.desktop`, `jdk.compiler` et `jdk.jartool`. Aucun téléchargement de dépendances pendant le build. Python 3 est seulement nécessaire pour la vérification indépendante facultative.

Depuis le dossier extrait :

```sh
# Linux / macOS : compiler, exécuter les tests, construire le JAR
sh build.sh
```

Sous Windows PowerShell :

```powershell
.\build.ps1
```

Les scripts appellent les outils du JDK via leurs modules (`java -m`), l'équivalent de `javac` et `jar`. Un JAR compilé est également fourni : `build/nist-face.jar`.

Essai immédiat sur l'image **synthétique** incluse (motif coloré, pas une vraie photo de visage) :

```sh
java -jar build/nist-face.jar convert examples/synthetic.png examples/metadata.properties essai.an2
java -jar build/nist-face.jar validate essai.an2 examples/synthetic.png
java -jar build/nist-face.jar extract essai.an2 extrait.png
```

`validate` compare également l'image embarquée à l'original lorsque le dernier argument est fourni. Cette égalité octet par octet s'applique au mode `preserve`. Les fichiers de sortie existants ne sont jamais remplacés : utilisez un nouveau nom pour recommencer.

## Utiliser votre photo

1. Copier `examples/metadata.properties` vers `metadata.properties`.
2. Remplacer les identifiants, dates et données métier par les valeurs réelles, suivant la spécification du destinataire.
3. Exécuter :

```sh
java -jar build/nist-face.jar convert visage.png metadata.properties visage.an2
java -jar build/nist-face.jar validate visage.an2 visage.png
```

Le programme ne déduit ni l'identité, ni la date de capture, ni l'organisme source à partir du PNG. Il ne détecte pas la présence d'un visage : `FACE` est la modalité déclarée par l'appelant. La photo doit être une image 2D en lumière visible. Aucune analyse d'identité ou de qualité biométrique n'est effectuée.

### Métadonnées

| Propriété | Champ produit | Valeur attendue |
|---|---|---|
| `transaction.type` | `1.004` | TOT convenu avec le destinataire, 1 à 16 lettres ASCII |
| `transaction.date` | `1.005` | Date locale de soumission, `yyyyMMdd` |
| `destination` | `1.007` | Identifiant de l'organisme destinataire |
| `origin` | `1.008` | Identifiant de l'organisme émetteur |
| `transaction.control` | `1.009` | TCN unique chez l'émetteur ; unicité à gérer dans votre application |
| `domain.name` | `1.013`, premier item | Domaine définissant notamment le type 2 |
| `domain.version` | `1.013`, second item | Version du domaine, facultative |
| `capture.source` | `10.004` | Organisme source de la capture |
| `capture.date` | `10.005` | Date de prise de vue, distincte de la date de transaction |
| `scale.units` | `10.008` | 0 : rapport de pixels ; 1 : pixels/pouce ; 2 : pixels/cm |
| `scale.x`, `scale.y` | `10.009`, `10.010` | Entiers 1 à 99999 ; égaux si l'unité est 1 ou 2 |
| `type2.NNN` | `2.NNN` | Champ défini par votre destinataire, numéro ≥ 3 |
| `image.mode` | Comportement du convertisseur | `preserve` (défaut) ou `normalize-rgb8` |

Pour une photo sans échelle physique connue dans le plan du sujet, utiliser `scale.units=0`. Le rapport `1/1` de l'exemple déclare des pixels carrés ; vérifiez cette propriété pour votre source. Les DPI d'impression présents dans un PNG ne prouvent pas une résolution physique au niveau du visage.

**Le type 2 n'a pas de dictionnaire démographique universel dans cette norme.** Le champ `2.003` de l'exemple signifie uniquement « référence synthétique du sujet » dans le domaine local fictif `DEMO`. Il n'est présenté ni comme un numéro de dossier officiel, ni comme un champ nom/prénom standard. Remplacez ces définitions par votre profil d'échange réel. Les champs `2.001` et `2.002` sont réservés au programme.

Les sous-champs et items structurés sont possibles dans un fichier Java Properties : `\u001e` représente RS et `\u001f` représente US. FS/GS et les autres contrôles sont interdits dans les valeurs. Les clés dupliquées, les alias numériques de champs et les propriétés inconnues sont rejetés.

### Traitement du PNG

**`preserve`** accepte les PNG RGB ou en niveaux de gris à 8 ou 16 bits par composante, sans canal alpha ni `tRNS`. Le fichier PNG entier est conservé : pixels, compression et métadonnées restent identiques. Le CSP est `GRAY` pour le gris, `SRGB` pour un RGB portant un chunk `sRGB`, sinon `RGB`. Le logiciel ne certifie pas les profils colorimétriques intégrés.

**`normalize-rgb8`** permet de traiter les PNG opaques à palette, à faible profondeur ou avec canal alpha entièrement opaque. Il redécode et réécrit un PNG RGB8 via Java ImageIO et `BufferedImage.getRGB`. Les pixels effectivement transparents sont refusés ; aucun fond arbitraire n'est ajouté. Cette opération peut perdre la précision 16 bits et les métadonnées. Elle n'est pas une conversion colorimétrique expertisée de tous les profils ICC/gamma possibles. Le PNG réécrit sans déclaration explicite sRGB est signalé `RGB`. L'égalité avec le fichier original n'est alors pas attendue.

Les APNG, les dimensions hors 10..99999, les images dépassant 20 millions de pixels et les PNG dépassant 32 Mio sont rejetés. Ce sont en partie des limites de cette implémentation. Les champs texte sont limités à 65 536 caractères, les valeurs métier du type 2 à un million de caractères au total et à 1 000 champs. Seuls les textes ASCII sont pris en charge : les accents sont **refusés, jamais remplacés silencieusement**. La gestion des jeux de caractères étendus de l'annexe A n'est pas implémentée. Les dates partielles, permises dans certains cas par la norme, ne sont pas prises en charge : cette API exige une date complète valide.

### Structure générée

| Enregistrement | Contenu |
|---|---|
| Type 1 | LEN calculé, VER=0502, CNT, TOT, DAT, DAI, ORI, TCN, NSR=NTR=00.00, DOM |
| Type 2 | LEN, IDC=00, champs métier configurés |
| Type 10 | LEN, IDC=01, IMT=FACE, SRC, PHD, dimensions, échelle, CGA=PNG, CSP, SAP=0, T2C=00, DATA |

Le CNT est `1<US>2<RS>2<US>00<RS>10<US>01` : le premier `2` compte les deux enregistrements **après** le type 1. Les IDC sont distincts et consécutifs ; `10.992=00` établit le lien biographique vers le type 2. Chaque LEN inclut les balises, les séparateurs et le FS final. Le lecteur utilise LEN pour extraire le binaire : un octet `0x1C` dans le PNG ne termine pas l'enregistrement.

## Utilisation comme bibliothèque Java

```java
import fr.example.nist.Config;
import fr.example.nist.NistConverter;
import fr.example.nist.NistValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

Config config = Config.load(Path.of("metadata.properties"));
byte[] png = Files.readAllBytes(Path.of("visage.png"));
byte[] transaction = NistConverter.convert(png, config);
NistValidator.Result result = NistValidator.validate(transaction);
Files.write(Path.of("visage.an2"), transaction, StandardOpenOption.CREATE_NEW);
System.out.println(result.width() + "x" + result.height());
```

L'API retourne un tableau d'octets. Pour les entrées non maîtrisées, utilisez une lecture bornée comme dans `Main` et dimensionnez la mémoire de votre service. La CLI retourne 0 en cas de succès et 2 en cas d'erreur ; elle ne contacte aucun service externe.

## Vérification reproductible

Le build exécute `TestSuite` : toute erreur produit un code de sortie non nul, sans dépendre de l'option Java `-ea`. Le rapport d'exécution fourni est `docs/test-results.txt`.

Les tests couvrent la conservation binaire, les dimensions, le gris 8/16 bits, les séparateurs dans l'image, les références IDC/CNT, les transitions de longueur décimale, les entrées invalides, les CRC PNG, les injections de séparateurs et les opérations CLI. Une transaction de référence créée par un encodeur Python distinct est aussi relue. Cette fixture est **synthétique**, pas un échantillon certifié NIST.

Vérification structurelle supplémentaire, avec un lecteur Python indépendant du Java :

```sh
python3 docs/check_independent.py essai.an2 examples/synthetic.png
```

Pour régénérer uniquement les deux fixtures synthétiques :

```sh
python3 docs/make_reference.py
```

`NistValidator` valide strictement **le sous-ensemble produit par ce projet**. Il peut refuser une transaction ANSI/NIST valide contenant d'autres champs, d'autres profils SAP ou une autre structure. Il vérifie les CRC des chunks PNG et décode l'image, mais ne remplace pas une suite exhaustive de conformité PNG ou biométrique.

Avant mise en production, faites contrôler des transactions par le destinataire avec **son profil exact** (TOT, domaine, dictionnaire type 2, format image accepté, champs supplémentaires). Les profils SAP 30/32/40+ imposent d'autres exigences : changer simplement `10.013` serait incorrect. Ils ne sont pas pris en charge ici. La documentation NIST de BioCTS consultée annonce AN-2011 et AN-2013 ; elle ne suffit pas à revendiquer un test officiel Update 2015. Aucun test avec le système réel du destinataire ni certification externe n'a été effectué.

## Sources et traçabilité

Référence principale : [NIST SP 500-290 Edition 3, ANSI/NIST-ITL 1-2011 Update 2015](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.500-290e3.pdf), consultée le 5 novembre 2026. Les sections sont plus fiables que les numéros de pages du lecteur PDF.

| Règle | Section de référence |
|---|---|
| LEN et DATA | 7.1, 7.2, annexe B.1.7 |
| IDC et lien biographique | 7.3.1, 8.10.52 |
| Version 0502 et CNT | 8.1.2, 8.1.3 |
| Métadonnées de transaction et domaine | 8.1.4–8.1.13 |
| Champs métier du type 2 | 8.2 et 8.2.3 |
| Champs du visage | 8.10, tableau 70 |
| SAP=0, profil inconnu | 7.7.5.1 et 8.10.13 |
| Échelles et rapport de pixels | 7.7.8.3–7.7.8.5 |
| Compression PNG et espaces de couleurs | Tableau 19, 7.7.10, tableau 28, 8.10.11 |
| Contraintes des profils de capture supérieurs | Annexe E, E.6.1 |

Autres sources : [spécification PNG W3C, deuxième édition](https://www.w3.org/TR/2003/REC-PNG-20031110/), [Java 17 BufferedImage](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/java/awt/image/BufferedImage.html), [BioCTS NIST](https://www.nist.gov/services-resources/software/biometric-testing-software/biometric-conformance-test-software-biocts-1).

## Fichiers principaux

- `src/main/java/fr/example/nist/` : convertisseur, configuration, lecteur/validateur, gestion PNG et CLI.
- `src/test/java/fr/example/nist/TestSuite.java` : tests exécutables.
- `examples/` : configuration et fichiers synthétiques de démonstration.
- `docs/` : rapport des tests, vérification indépendante et génération de fixture.
- `build/nist-face.jar` : exécutable compilé.

Les données d'exemple sont fictives. La suite vérifie le contenant technique ; elle n'atteste pas la présence ou la qualité d'un visage.
