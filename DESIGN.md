# DESIGN — Cauchemar

Document de conception vivant. Il décrit ce que le mod **est** aujourd'hui et ce qu'il **vise**.
Toute feature qui arrive dans le code arrive aussi ici, dans la même PR. Si ce n'est pas écrit ici,
ce n'est pas dans le mod.

## Vision

Cauchemar est un mod d'horreur immersive pour Minecraft. Il n'ajoute pas un boss de plus à taper :
il ajoute une présence. Le joueur doit sentir qu'il n'est plus seul dans sa grotte avant même
d'avoir vu quoi que ce soit.

Principes qui tranchent les arbitrages :

- **La tension avant le combat.** Ce que le joueur entend, devine et redoute compte plus que les
  dégâts infligés.
- **L'obscurité est le territoire de la créature.** La lumière est l'outil défensif du joueur.
- **Peu de contenu, mais crédible.** Une seule créature bien faite vaut mieux que cinq approximatives.
- **La rencontre se mérite.** Le joueur tombe sur les traces avant de tomber sur la bête.

## État actuel

Le projet a été remis à plat le 2026-08-13. La couche déplacement / IA / perception de la première
itération a été archivée sur la branche `archive/spider-v1` et retirée du code actif, parce qu'elle
avait grossi plus vite que notre maîtrise du sujet.

Ce qui existe aujourd'hui :

| Élément | État |
|---|---|
| Socle NeoForge (entrypoints, config, registries) | En place |
| Assets Mother Spider (modèle, texture, 5 animations) | En place |
| Entité `cauchemar:mother_spider` | Existe et s'affiche, joue `animation.idle`, ne fait rien d'autre |
| Bloc `cauchemar:spider_egg` | En place, décoratif |
| Déplacement, IA, combat | Néant |
| Spawn naturel | Néant, l'entité n'apparaît que par `/summon` |

## Features

Une section par feature livrée. Ajoutée dans la PR qui livre la feature.

### Mother Spider (entité)

La créature centrale du mod. Grande araignée aux longues pattes, pensée pour les grottes.

- Id : `cauchemar:mother_spider`, catégorie `MONSTER`
- Rendu via GeckoLib, modèle `geo/entity/mother_spider.geo.json`
- Animations disponibles dans l'asset : `idle`, `walk`, `observe`, `turn_left`, `air`.
  Seule `idle` est câblée pour l'instant
- Hitbox 0.9 x 0.8 : le corps seulement, les pattes débordent visuellement. Valeur provisoire,
  à revoir avec le déplacement
- Aucun goal, aucune navigation custom : elle est immobile

### Spider Egg (blocs)

Une masse d'œufs enveloppée de soie, dans deux états. Matière première du futur nid.

- Ids : `cauchemar:spider_egg` (intact) et `cauchemar:spider_egg_hatched` (éclos, affaissé, sans
  dôme et avec une jupe plus basse)
- **Les deux partagent tout** : la classe `SpiderEggBlock`, les propriétés, les sons, le
  ralentissement. Seule la forme diffère, et elle est passée au constructeur :
  `1,0,2 → 15,16,14` pour l'intact, `1,0,2 → 15,10,14` pour l'éclos. Chacun garde en revanche sa
  propre texture de bloc et sa propre icône d'objet
- Modèle exporté de Blockbench (`spider_egg.bbmodel`, format Java Block/Item), 3 éléments :
  `mass` (la ponte), `dome` (le renflement au-dessus), `overlay` (la jupe de soie, qui ne monte qu'à
  10 sur 16)
- **Résistant, sauf à la lame** : résistance 3.75, juste sous les 4.0 d'une toile, et l'épée coupe
  15 fois plus vite. Il faut donc une lame pour dégager un nid rapidement, la main y arrive mais
  péniblement (environ 5,5 s par œuf contre 0,4 s à l'épée)
- Ce bonus est implémenté dans `getDestroyProgress` du bloc, faute de pouvoir passer par un tag
  existant : vanilla accorde le sien via une règle **codée en dur sur `Blocks.COBWEB`** dans le
  composant outil de l'épée comme des cisailles, et le tag `sword_efficient` ne donnerait que 1,5x.
  Les outils concernés sont listés dans notre propre tag **`cauchemar:cuts_spider_eggs`**
  (`#minecraft:swords` et les cisailles), modifiable par datapack. NeoForge a bien un tag
  `c:tools/shear`, mais sa documentation interdit de s'en servir pour décider d'un comportement
  d'outil
- Pas de `requiresCorrectToolForDrops`, volontairement : le même mécanisme de règle rendrait le
  bloc impossible à ramasser, puisque aucun outil ne peut être déclaré « correct » pour lui
- **Brûle et cède aux explosions.** Inflammabilité aux valeurs de la laine (propagation 30, combustion
  60), et résistance au souffle abaissée à 0,5 alors que la dureté reste à 3,75 : `strength(x)` fixe
  les deux à la même valeur, d'où la forme à deux arguments. L'inflammabilité a un second effet,
  voulu : le feu ne tient que si le bloc sous lui a une face supérieure pleine **ou** si un voisin
  est inflammable. Les œufs échouent au premier test, leur forme ne remplissant pas le haut du cube,
  donc c'est l'inflammabilité qui permet de poser du feu contre un nid
- **Butin** : 1 à 2 ficelles pour l'œuf intact, 0 à 1 pour l'éclos qui est déjà vidé de sa soie,
  augmentées par la Fortune dans les deux cas, ou le bloc lui-même avec des cisailles ou le Toucher
  de Soie. Entièrement dans la table de butin, sans code, calqué sur la toile vanilla
  pour la récupération et sur les minerais pour la Fortune. Deux choix de formule à connaître :
  - `uniform_bonus_count` et non `ore_drops` : la première ajoute un tirage, la seconde multiplie le
    lot. Vanilla emploie `ore_drops` pour les butins unitaires (diamant, charbon) et
    `uniform_bonus_count` pour ceux qui sont déjà multiples (redstone, lapis), ce qui est notre cas ;
  - `explosion_decay` et non la condition `survives_explosion` : la fonction réduit le nombre
    d'objets, là où la condition annule l'entrée entière. Sur un butin multiple, c'est la fonction
    qu'il faut, et c'est ce que font les minerais
- Bloc plein, mais **pas un cube complet** : l'enveloppe fait 14 blocs de profondeur sur 16. D'où
  le `noOcclusion()` sur ses propriétés, sans lequel les blocs voisins masqueraient leurs faces
  contre lui et on verrait le vide par la marge d'un pixel
- **La forme ignore l'enveloppe de soie.** `SpiderEggBlock.getShape` renvoie la boîte englobante de
  `mass` et `dome` seulement, `Block.box(1, 0, 2, 15, 16, 14)`, exactement celle de l'œuf de sniffer
  vanilla. La soie est purement décorative : on ne la vise pas et on ne s'y cogne pas. Comme cette
  forme sert à la fois au contour de visée et à la collision, il n'y a qu'une méthode à surcharger
- **Sons propres**, 4 variantes par événement, tirées au sort par Minecraft à chaque déclenchement :
  casse et pose partagent les mêmes clips de 0,8 s, pas et coups les mêmes clips de 0,41 s. Seule la
  chute reste sur le son de la laine. À savoir sur le son de coup : le minage le rejoue toutes les
  4 ticks à un pitch de 0,5, ce qui étire un clip de 0,41 s à 0,82 s, donc environ quatre instances
  se superposent en permanence. C'est ce qui impose des clips courts pour cet emplacement
- **Texture de particules dédiée** (`<id>_particle.png`, 16x16), déclarée sous la clé `particle` du
  modèle. Sans elle, les particules de coup et de casse piochent leur apparence **au hasard dans
  l'atlas entier** : le moteur le découpe en grille 4x4 et tire une case, or la moitié basse de nos
  atlas est vide et une partie du reste est de la soie. Résultat, une particule sur deux était
  invisible et les autres montraient l'enveloppe. La position, elle, était déjà juste : les
  particules naissent dans `getShape`, donc dans les œufs
- **La jupe de soie n'est pas ombrée** (`"shade": false` sur son élément), ce qui la rapproche de la
  toile vanilla. Minecraft assombrit normalement chaque face selon son orientation (dessus à pleine
  lumière, nord et sud à 80 %, est et ouest à 60 %, dessous à 50 %) ; `shade` exempte un élément de
  ce dégradé. C'est ce que fait `block/cross`, dont hérite la toile, et c'est toute la raison de sa
  clarté. Le champ est par élément, donc les œufs gardent leur relief
- **Icône d'inventaire dédiée** : `textures/item/spider_egg.png`, un sprite 16x16, via un modèle
  d'objet en `minecraft:item/generated`. Conséquence : l'objet est plat dans tous les contextes, y
  compris tenu en main. Le rendre en volume demanderait de revenir à un modèle d'objet héritant du
  modèle de bloc, et donc de renoncer au sprite
- **Ralentit qui le touche**, plus doucement qu'une toile. Il faut deux mécanismes, car aucun ne
  couvre tous les contacts :
  - `entityInside` applique `makeStuckInBlock` avec `0,7 / 0,6 / 0,7` quand une entité **empiète**
    sur le cube. Cela couvre le frôlement latéral, le saut contre le bloc et la chute le long. Pour
    situer : une toile applique `0,25 / 0,05 / 0,25`, un buisson de baies `0,8 / 0,75 / 0,8` ;
  - `speedFactor` à 0,75 couvre le seul cas que le premier laisse passer, marcher **sur** le bloc :
    une entité posée dessus ne l'empiète jamais. Cette propriété n'agit que sur l'horizontale.
- Deux effets de bord de `makeStuckInBlock`, assumés et identiques à la toile : il remet la distance
  de chute à zéro (donc pas de dégâts en tombant contre le bloc), et il annule l'inertie à chaque
  tick de contact, ce qui rend l'effet plus collant que le seul multiplicateur ne le laisse croire.
- **À faire quand la Mother Spider se déplacera** : l'exempter de son propre nid. Vanilla fait
  exactement cela, `Spider.makeStuckInBlock` ignore l'effet pour la toile et rien d'autre.
- **Éclosion à la casse** : déchirer un œuf **intact** a 15 % de chances de libérer une araignée, et
  une éclosion sur dix donne une araignée des cavernes plutôt qu'une ordinaire (soit 13,5 % et 1,5 %
  au total). L'œuf éclos est vide et ne donne jamais rien. Rien n'éclot non plus quand l'œuf est
  récolté entier, aux cisailles ou au Toucher de Soie : il n'y a alors pas d'œuf brisé, il est dans
  l'inventaire. Calqué sur les blocs infestés vanilla, qui libèrent un poisson d'argent par le même
  point d'entrée (`spawnAfterBreak`), respectent la règle `doBlockDrops` et utilisent le tag
  d'enchantement `prevents_infested_spawns`
- Aucun autre comportement. Le reste viendra avec le nid
- Rangé dans l'onglet créatif « Blocs naturels »

Quatre retouches sont à réappliquer **à chaque ré-export depuis Blockbench**, qui les écrase :

1. préfixer les textures en `cauchemar:block/…`, Blockbench sortant un nom nu que Minecraft
   résoudrait dans `minecraft:` ;
2. ajouter `"parent": "minecraft:block/block"`, sans quoi l'objet tenu en main et l'icône
   d'inventaire n'ont aucune transformation d'affichage ;
3. ajouter `"render_type": "minecraft:cutout"`. C'est un champ NeoForge, pas vanilla. Par défaut un
   bloc est rendu en `solid`, une couche qui ignore le canal alpha et peint tout pixel transparent
   en **noir**. `cutout` est la couche du verre et de la toile : chaque pixel est soit plein, soit
   absent, ce qui correspond exactement à la texture (alpha 0 ou 255, jamais entre les deux). Une
   texture en semi-transparence exigerait `translucent` à la place ;
4. remettre `"shade": false` sur l'élément `overlay`.

Le reste du fichier est l'export brut, pour que remplacer le modèle reste une simple copie.

**Limitation connue et acceptée : les fissures de minage se dessinent aussi sur la jupe de soie.**
Minecraft repeint la texture de fissure sur chaque face du modèle, et cette texture est opaque quelle
que soit celle du bloc, donc les zones transparentes de la soie ressortent en plein pendant le
minage. La parade a été cherchée puis écartée, et le résultat de l'enquête vaut d'être gardé :

- passer la soie dans un `BlockEntityRenderer` **ne marche pas**. Le rendu des entités de bloc reçoit
  lui aussi les fissures, dès que le type de rendu porte `affectsCrumbling` (`LevelRenderer`, autour
  de la ligne 1072), ce qui est le cas de tous les types courants, y compris ceux des entités. Un
  coffre en cours de minage montre bien ses fissures. En prime, cette voie perd le dégradé
  directionnel et l'occlusion ambiante, calculés uniquement dans le chemin de rendu du chunk ;
- la seule parade viable serait d'intercepter la passe de fissures, qui interroge le modèle avec un
  `RenderType` à `null`, via un `BakedModelWrapper` substitué à la cuisson. Écartée pour l'instant :
  la documentation NeoForge demande de renvoyer toutes ses faces dans ce cas.

Le défaut a plutôt été réduit à la source, en abaissant la jupe de soie à 10 de haut au lieu de 16.
Il ne se voit que pendant le minage d'un œuf, jamais au repos.

## Roadmap

Dans l'ordre. Chaque ligne est une PR.

1. **Le nid** : structure générée dans les grottes, dans laquelle l'araignée apparaît. Donne au
   joueur un lieu à découvrir et à la créature une raison d'être là. *(en cours de conception)*
2. **Le déplacement** : refonte complète, reprise depuis zéro et par étapes maîtrisées.
3. La suite se décide au fur et à mesure. Pistes ouvertes : sons d'ambiance, traces de toile,
   détection du joueur, comportement de chasse.

## Conventions techniques

| Sujet | Convention |
|---|---|
| Mod id | `cauchemar` |
| Package Java | `com.payangar.cauchemar` |
| Minecraft / NeoForge | 1.21.1 / 21.1.233, Java 21, Mojmap + Parchment |
| Animation | GeckoLib 4.8.4 |
| Branches | une branche Git par version Minecraft, `1.21.1` est la branche par défaut |
| Assets entité | `geo/entity/<id>.geo.json`, `textures/entity/<id>.png`, `animations/entity/<id>.animation.json` |
| Assets bloc | `blockstates/<id>.json`, `models/block/<id>.json`, `models/item/<id>.json`, `textures/block/<id>.png`, `textures/item/<id>.png` si icône dédiée, `data/cauchemar/loot_table/blocks/<id>.json` |
| Assets son | `sounds/<catégorie>/<id>/<nom><n>.ogg`, déclarés dans `sounds.json`. **OGG Vorbis et mono obligatoires** : l'Opus n'est pas lu, et un fichier stéréo n'est pas spatialisé (même volume partout dans le monde) |

Organisation du code Java :

```
com.payangar.cauchemar
  Cauchemar.java          point d'entrée commun, branche les registries
  CauchemarClient.java    point d'entrée client, branche les renderers
  Config.java             ModConfigSpec NeoForge
  registry/               un fichier par type de registre (ModBlocks, ModItems, ModEntities)
  entity/                 les entités
  client/                 rendu, modèles GeckoLib
```
