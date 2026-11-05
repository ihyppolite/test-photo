# Validation exécutée le 5 novembre 2026

- Environnement : Linux, OpenJDK 17.0.20.
- Compilation des sources et tests avec le compilateur du JDK 17.
- Exécution réelle de `sh build.sh` : **60 tests réussis**, construction du JAR réussie.
- Le JAR fourni est issu de cette compilation.
- Exécution réelle de `validate` sur `examples/generated.an2` avec comparaison de l'image source : réussite.
- Vérification structurelle du même fichier par `docs/check_independent.py` : réussite.
- Test de lecture d'une référence encodée par `docs/make_reference.py` : réussite.
- Résultats détaillés : `test-results.txt` et `cli-validation.txt`.

La commande `build.ps1` est fournie pour Windows, mais n'a pas été exécutée sous Windows dans cet environnement. Aucune validation externe par NIST, BioCTS ou par le destinataire final n'a été exécutée.

## Ce que ces résultats établissent

La construction du sous-ensemble implémenté, son décodage, les liens entre les trois enregistrements, les contrôles d'intégrité mis en œuvre et la conservation des octets PNG en mode preserve fonctionnent sur les cas testés. Des mutations ciblées sont rejetées, y compris un décalage de LEN, un CNT faux et un PNG corrompu.

## Ce qu'ils n'établissent pas

Ils n'établissent ni une conformité exhaustive à chaque disposition de la norme, ni la qualité d'une photo de visage, ni la conformité à un profil métier inconnu. Les dates/identités/organismes fournis par l'appelant et l'unicité globale des TCN ne peuvent pas être authentifiés par ce logiciel. Les champs du type 2 restent à définir selon la spécification du destinataire.

Les limites de l'implémentation (ASCII, SAP=0, PNG, trois enregistrements, dates complètes, limites mémoire) sont décrites dans le README. Les fixtures représentent un motif synthétique et ne constituent pas une donnée biométrique de référence.
