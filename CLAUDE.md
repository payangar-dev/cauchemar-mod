# Cauchemar — règles de travail

Mod NeoForge 1.21.1. La conception vit dans [DESIGN.md](DESIGN.md), à lire avant toute intervention.
Le détail de chaque feature vit dans `docs/`.

## On conçoit ensemble, avant de coder

C'est la règle la plus importante du projet, et elle prime sur l'envie d'avancer vite.

Avant d'écrire du code pour une feature, expose à Pierre : ce que tu vas faire, par quel mécanisme
Minecraft ça passe, ce que ça touche ailleurs dans le mod, et les alternatives quand il y en a.
Attends son accord. Une feature dont il ne comprend pas le fonctionnement est une dette, pas un gain.

Corollaire : ne pars jamais d'un besoin flou. Si la demande admet plusieurs lectures, pose la
question au lieu de choisir à sa place.

## La documentation suit le code, sur deux niveaux

Toute feature livrée est documentée dans la PR qui la livre. Pas de PR de code sans mise à jour de la
documentation, pas de documentation en avance sur le code. Ce qui n'y figure pas n'existe pas.

Deux niveaux, et le mélange des deux est la faute à éviter :

- **`DESIGN.md`** porte la logique d'ensemble du mod : la vision, ce que chaque feature apporte au
  joueur, comment elles s'articulent. Quelques lignes par feature, du point de vue du joueur. Aucune
  valeur chiffrée, aucun nom de classe, aucun détail de moteur.
- **`docs/<feature>.md`** porte l'implémentation : les valeurs et pourquoi celles-là, les classes et
  les points d'entrée, les pièges du moteur rencontrés, les pistes explorées puis écartées et la
  raison. C'est là qu'on peut être long, et c'est ce qui évite de refaire deux fois la même enquête.

Une page `docs/` renvoie à `DESIGN.md` pour l'intention, et `DESIGN.md` renvoie à la page pour le
détail.

## Une feature, une PR

Rien ne va directement sur `1.21.1`. Chaque feature part sur sa branche, passe par une PR, et cette
PR contient le code, les assets et la mise à jour du `DESIGN.md`.

## Rester à la portée de ce qu'on maîtrise

Le projet a déjà été remis à plat une fois pour avoir couru trop loin devant. Préfère la solution
la plus simple qui marche, quitte à la complexifier plus tard. Une brique qu'on ne sait pas
expliquer est une brique à ne pas poser.

## Divers

- Vérifie les API NeoForge et GeckoLib dans la doc ou les sources avant de t'en servir, jamais de
  mémoire. Pour lire le code d'un mod externe, passer par `/lookup-mc-mod-source`.
- Commentaires de code, messages de commit et descriptions de PR en anglais. Le reste (DESIGN.md,
  échanges) en français.
- Compilation : `./gradlew compileJava`. Test en jeu : `./gradlew runClient`.
