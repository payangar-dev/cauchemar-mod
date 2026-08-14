# Araignées en particules

Détail d'implémentation de la particule `cauchemar:spider` et de son peuplement autour des œufs. Pour
ce qu'elle apporte au mod, voir [DESIGN.md](../DESIGN.md). Le bloc source est décrit dans
[spider-eggs.md](spider-eggs.md).

Trois fichiers : `SpiderParticle` (le comportement), `SpiderNursery` (le peuplement),
`ModParticles` (le type). Le tout est **strictement client** : rien n'est synchronisé, rien n'est
sauvegardé, et deux joueurs ne voient pas les mêmes araignées aux mêmes endroits. C'est assumé, ce
sont des particules ; les faire tenir par le serveur reviendrait à en faire des entités.

## Les frames

`particles/spider.json` liste trois textures : deux de marche, puis une de mort. Une particule
déclare ses frames comme une **liste de fichiers**, contrairement aux blocs et aux objets qui
demandent une texture unique empilée avec un `.mcmeta`.

Deux pièges dans la façon dont le moteur choisit la frame :

- `setSpriteFromAge`, la méthode toute faite, **étale les frames sur la durée de vie** : deux images
  sur trois secondes donnent un changement de pose, pas une démarche. La marche boucle donc à la
  main, une image toutes les 4 ticks ;
- `SpriteSet.get(age, maxAge)` calcule `age * (frames - 1) / maxAge`. Pour viser un index précis il
  faut lui passer `TOTAL_FRAMES - 1` comme `maxAge`. Ajouter la frame de mort sans corriger ce
  calcul faisait sauter la deuxième image du cycle de marche.

L'animation suit le **temps de marche** et non l'âge, sans quoi une araignée à l'arrêt continuerait
d'agiter ses pattes sur place.

## Marcher sur le terrain

C'est le cœur du fichier. La particule **n'utilise pas la physique des particules** : vanilla la
ferait tomber sous la gravité et la poserait exactement sur une face de bloc, ce qui l'aplatit au sol
et fait vibrer le quad contre cette face.

Elle tient à la place trois choses : la **face** à laquelle elle est collée, un **cap** dans le plan
de cette face, une **allure**. Chaque tick elle avance dans ce plan, puis tâte le terrain :

| Ce qu'elle rencontre | Ce qu'elle fait |
|---|---|
| Un mur devant | bascule sur sa face, son ancien dessus devenant sa direction de marche |
| Plus de sol | contourne l'arête et repart dessous, tête en bas |
| Rien de particulier | avance |

Le plafond n'a demandé aucun code : c'est le même mécanisme avec une normale vers le bas. Après
chaque pas elle se recale à deux centièmes de bloc de sa face, ce qui remplace la physique et vaut
sur les six orientations.

**À la naissance, elle se cherche un appui** jusqu'à trois blocs plus bas, et se supprime si elle
n'en trouve pas. Sans cela, une araignée lâchée en l'air glisse à l'horizontale à travers le monde,
puisqu'elle ne sait pas tomber. C'est ce qui arrivait à l'essaim, né dans le bloc de l'œuf au moment
même où celui-ci devient de l'air.

## L'orientation du sprite

Le quad est couché par `getFacingCameraMode`, un point d'extension de `SingleQuadParticle` : il donne
la rotation à appliquer avant le dessin. Aucun code de rendu à réécrire, ce que confirme le fait que
vanilla y expose déjà deux modes (face caméra, et caméra en Y seulement).

Le **roulis** oriente ensuite la tête dans la direction de marche, et il se mesure contre les axes du
quad une fois couché, jamais contre le cap interne. Ce cap vit dans une base tangente construite par
un produit vectoriel dont le vecteur de référence change selon la face : elle ne coïncide avec les
axes du sprite que par accident, ce qui donnait une orientation juste sur certaines faces et fausse
sur les autres. La tête étant en bas de la texture, l'alignement se fait sur l'axe descendant.

## Deux populations

Une seule classe, distinguée par la **vitesse initiale** reçue : lancée, donc en fuite ; sans élan,
donc née d'un œuf intact.

| | Couvée | Fuite |
|---|---|---|
| Origine | œuf intact, via la nursery | œuf cassé, une chance sur trois |
| Allure | 0,02 à 0,055 | 0,09 à 0,17 |
| Durée de vie | illimitée | 3 s, fondu compris |
| Pauses | oui | jamais |
| Rapport à son œuf | rappelée au-delà de 6 blocs | ne se retourne jamais vers lui |

Ce marquage par la vitesse est implicite, et c'est le point le plus discutable du fichier. Deux types
de particules déclarés séparément seraient plus explicites, au prix d'un doublon d'assets.

## Le peuplement

`SpiderNursery` tient, pour chaque œuf, la liste de ses araignées vivantes et la date de son prochain
essai : au plus **2 à la fois**, un essai toutes les **5 s** avec **30 %** de réussite, et plus
d'essai du tout une fois le compte atteint.

Le déclencheur est l'`animateTick` du bloc, que le client tire au hasard pour les blocs proches du
joueur, environ une fois toutes les cinq à sept ticks. Ce hasard ne décide donc que du **moment où
l'on regarde** ; l'intervalle et le plafond sont tenus par la nursery.

Deux points de prudence :

- **le `BlockPos` reçu est mutable et réutilisé.** Vanilla en garde un seul qu'il repositionne des
  centaines de fois par tick. Tout ce qui le conserve au-delà de l'appel doit en prendre une copie,
  sous peine de pointer sur un bloc quelconque au tick suivant. C'est exactement le bug qui faisait
  se croire orphelines toutes les araignées ;
- **`animateTick` est du code commun**, que Minecraft se contente d'appeler depuis le client. La
  garde `isClientSide` autour de l'appel à la nursery n'est pas décorative : elle empêche un serveur
  dédié de résoudre une classe client absente.

Une araignée surveille son œuf toutes les dix ticks et, s'il a disparu, se donne **2 à 3 secondes**.
Cette veille lui appartient et ne peut pas revenir à la nursery : un œuf cassé n'est plus tické, donc
la nursery n'apprendrait jamais sa propre disparition.

## Mourir

Un joueur qui **se déplace** écrase une araignée sous ses pieds. Le test porte sur le déplacement du
joueur pendant le tick, jamais sur celui de l'araignée : c'est ce qui distingue un pied qui vient à
elle d'une araignée qui passe sous quelqu'un d'immobile, laquelle ne risque rien.

L'écrasée se fige sur sa frame de mort, reste 1,25 s bien visible, puis s'estompe en 1 s. La nursery
la retire de sa liste, et l'œuf peut la remplacer au cycle suivant.

Le fondu impose `PARTICLE_SHEET_TRANSLUCENT`. Le nom pousse à choisir `PARTICLE_SHEET_LIT`, mais
celui-ci appelle `RenderSystem.disableBlend()` : l'alpha y est purement ignoré et aucun fondu n'est
possible. Les deux éclairent le sprite de la même façon.

Les araignées de couvée n'ont pas de fondu de fin de vie : sans durée de vie, elles ne s'éteignent
que par déchargement de chunk, ou au-delà de 48 blocs du joueur pour éviter qu'elles s'accumulent
dans un chunk qui reste chargé. À cette distance un sprite de 20 cm est indistinguable, donc rien ne
s'éteint jamais sous les yeux du joueur.

## À faire

- Trancher le marquage implicite couvée / fuite, aujourd'hui porté par la vitesse initiale.
- Voir ce que donne un nid entier : la logique est **par œuf**, donc vingt œufs peuvent entretenir
  une quarantaine d'araignées.
