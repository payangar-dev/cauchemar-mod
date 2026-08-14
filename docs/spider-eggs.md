# Œufs d'araignée

Détail d'implémentation des blocs `cauchemar:spider_egg` et `cauchemar:spider_egg_hatched`. Pour ce
qu'ils sont et à quoi ils servent dans le mod, voir [DESIGN.md](../DESIGN.md).

## Structure

Les deux blocs partagent la classe `SpiderEggBlock`, les mêmes propriétés et les mêmes sons. Ne
diffèrent que trois choses, passées au constructeur ou portées par les assets :

| | Intact | Éclos |
|---|---|---|
| Forme | `1,0,2 → 15,16,14` | `1,0,2 → 15,10,14` |
| Chance d'éclosion | 15 % | 0 |
| Textures | les siennes | les siennes |

Le modèle vient de Blockbench (`spider_egg.bbmodel`, format Java Block/Item) et comporte trois
éléments : `mass` (la ponte), `dome` (le renflement, absent de l'éclos) et `overlay` (la jupe de
soie, qui ne monte qu'à 10 sur 16).

## Rendu

**La forme ignore la jupe de soie.** `getShape` renvoie la boîte englobante de `mass` et `dome`
seulement, ce qui se trouve être exactement celle de l'œuf de sniffer vanilla. La soie est purement
décorative : on ne la vise pas, on ne s'y cogne pas. Cette forme sert à la fois au contour de visée
et à la collision, il n'y a donc qu'une méthode à surcharger.

**`noOcclusion` est obligatoire.** Aucun des deux modèles ne remplit son cube. Sans lui, les blocs
voisins masqueraient leurs propres faces contre l'œuf et on verrait le vide par la marge.

**La jupe n'est pas ombrée** (`"shade": false` sur son élément), ce qui la rapproche de la toile.
Minecraft assombrit normalement chaque face selon son orientation : dessus à pleine lumière, nord et
sud à 80 %, est et ouest à 60 %, dessous à 50 %. `shade` exempte un élément de ce dégradé, et c'est
toute la raison de la clarté d'une toile, dont le modèle `block/cross` fait la même chose. Le champ
étant par élément, les œufs gardent leur relief.

**Icône d'inventaire dédiée**, un sprite 16x16 par bloc, via un modèle d'objet en
`minecraft:item/generated`. Conséquence : l'objet est plat dans tous les contextes, y compris tenu
en main. Le rendre en volume imposerait de revenir à un modèle héritant du modèle de bloc, donc de
renoncer au sprite.

**Texture de particules dédiée** (`<id>_particle.png`, 16x16), sous la clé `particle` du modèle.
Sans elle, les particules de coup et de casse tirent leur apparence **au hasard dans l'atlas
entier** : le moteur le découpe en grille 4x4 et pioche une case, or la moitié basse de nos atlas
est vide et une partie du reste est de la soie. Une particule sur deux ressortait invisible, les
autres montraient l'enveloppe. Leur position, elle, était déjà juste : elles naissent dans
`getShape`, donc dans les œufs.

### Retouches à réappliquer après chaque ré-export Blockbench

L'export écrase ces quatre points, et le reste du fichier est laissé brut pour que remplacer le
modèle reste une simple copie.

1. préfixer les textures en `cauchemar:block/…`, Blockbench sortant un nom nu que Minecraft
   résoudrait dans `minecraft:` ;
2. ajouter `"parent": "minecraft:block/block"`, sans quoi l'objet en main et l'icône d'inventaire
   n'ont aucune transformation d'affichage ;
3. ajouter `"render_type": "minecraft:cutout"`, un champ NeoForge et non vanilla. Par défaut un bloc
   est rendu en `solid`, une couche qui ignore le canal alpha et peint tout pixel transparent en
   **noir**. `cutout` est la couche du verre et de la toile : chaque pixel est soit plein soit
   absent, ce qui correspond à nos textures (alpha 0 ou 255, jamais entre les deux). Une texture en
   semi-transparence exigerait `translucent` ;
4. remettre `"shade": false` sur l'élément `overlay`.

### Limitation connue : les fissures de minage marquent aussi la soie

Minecraft repeint la texture de fissure sur chaque face du modèle, et cette texture est opaque quelle
que soit celle du bloc. Les zones transparentes de la jupe ressortent donc en plein pendant le
minage. La parade a été cherchée puis écartée, et l'enquête vaut d'être gardée :

- passer la soie dans un `BlockEntityRenderer` **ne marche pas**. Le rendu des entités de bloc reçoit
  lui aussi les fissures dès que le type de rendu porte `affectsCrumbling` (`LevelRenderer`, autour
  de la ligne 1072), ce qui est le cas de tous les types courants, y compris ceux des entités : un
  coffre en cours de minage montre bien ses fissures. Cette voie perd en prime le dégradé
  directionnel et l'occlusion ambiante, calculés uniquement dans le chemin de rendu du chunk ;
- la seule parade viable serait d'intercepter la passe de fissures, qui interroge le modèle avec un
  `RenderType` à `null`, via un `BakedModelWrapper` substitué à la cuisson. Écartée : la
  documentation NeoForge demande de renvoyer toutes ses faces dans ce cas.

Le défaut a été réduit à la source en abaissant la jupe à 10 de haut au lieu de 16. Il ne se voit
que pendant le minage, jamais au repos.

## Physique

**Ralentissement au contact**, plus doux qu'une toile. Deux mécanismes sont nécessaires, aucun ne
couvrant tous les cas :

- `entityInside` applique `makeStuckInBlock` avec `0,9 / 0,85 / 0,9` quand une entité **empiète** sur
  le cube, ce qui couvre le frôlement latéral, le saut contre le bloc et la chute le long. Pour
  situer : une toile applique `0,25 / 0,05 / 0,25`, un buisson de baies `0,8 / 0,75 / 0,8` ;
- `speedFactor` à 0,75 couvre le seul cas que le premier laisse passer, marcher **sur** le bloc : une
  entité posée dessus ne l'empiète jamais. Cette propriété n'agit que sur l'horizontale.

Deux effets de bord de `makeStuckInBlock`, assumés et identiques à la toile : il remet la distance de
chute à zéro (pas de dégâts en tombant contre le bloc) et il annule l'inertie à chaque tick de
contact, ce qui rend l'effet plus collant que le multiplicateur seul ne le laisse croire.

**Résistance 3,75**, juste sous les 4,0 d'une toile, mais **résistance au souffle à 0,5** : la soie
cède à toute explosion. `strength(x)` fixe les deux à la même valeur, d'où la forme à deux arguments.

**Inflammable** aux valeurs de la laine (propagation 30, combustion 60). Second effet, voulu : le feu
ne tient que si le bloc sous lui a une face supérieure pleine **ou** si un voisin est inflammable.
Les œufs échouent au premier test, leur forme ne remplissant pas le haut du cube, donc c'est
l'inflammabilité qui permet de poser du feu contre un nid.

## Outils

**Épées et cisailles coupent 15 fois plus vite**, comme sur une toile. Implémenté dans
`getDestroyProgress` du bloc, faute de pouvoir passer par un tag existant : vanilla accorde ce bonus
via une règle **codée en dur sur `Blocks.COBWEB`** dans le composant outil de l'épée comme des
cisailles, et le tag `sword_efficient` ne donnerait que 1,5x. Les outils concernés sont listés dans
notre tag **`cauchemar:cuts_spider_eggs`** (`#minecraft:swords` et les cisailles), modifiable par
datapack. NeoForge a bien un tag `c:tools/shear`, mais sa documentation interdit de s'en servir pour
décider d'un comportement d'outil.

**Pas de `requiresCorrectToolForDrops`**, volontairement : le même mécanisme de règle rendrait le
bloc impossible à ramasser, puisque aucun outil ne peut être déclaré « correct » pour lui.

Temps de casse qui en résultent : environ 0,4 s à la lame, 5,5 s à mains nues. Une toile demande 20 s
à mains nues, mais elle le doit à `requiresCorrectToolForDrops`.

## Butin

Tout est dans la table de butin, sans code, calqué sur la toile vanilla pour la récupération et sur
les minerais pour la Fortune.

| Outil | Résultat |
|---|---|
| Main, épée | 1 à 2 ficelles (intact), 0 à 1 (éclos) |
| Fortune | ajoute 0 à N ficelles, N étant le niveau |
| Cisailles, Toucher de Soie | le bloc lui-même |

Deux choix de formule à connaître :

- `uniform_bonus_count` et non `ore_drops` : la première ajoute un tirage, la seconde multiplie le
  lot. Vanilla emploie `ore_drops` pour les butins unitaires (diamant, charbon) et
  `uniform_bonus_count` pour ceux déjà multiples (redstone, lapis), ce qui est notre cas ;
- `explosion_decay` et non la condition `survives_explosion` : la fonction réduit le nombre d'objets
  là où la condition annule l'entrée entière. Sur un butin multiple c'est la fonction qu'il faut, et
  c'est ce que font les minerais.

## Éclosion

Déchirer un œuf **intact** a 15 % de chances de libérer une araignée, dont une sur dix est une
araignée des cavernes : 13,5 % et 1,5 % au total. L'œuf éclos est vide et ne donne jamais rien.

Calqué sur les blocs infestés vanilla, qui libèrent un poisson d'argent par le même point d'entrée,
`spawnAfterBreak`, appelé côté serveur avec l'outil en paramètre. Trois garde-fous repris d'eux :

- rien n'éclot si l'œuf est récolté **entier**, aux cisailles ou au Toucher de Soie : il n'y a alors
  pas d'œuf brisé, il est dans l'inventaire. Le test passe par le tag d'enchantement vanilla
  `prevents_infested_spawns`, qui ne contient que le Toucher de Soie ;
- la règle `doBlockDrops` est respectée ;
- l'apparition passe par `EntityType.spawn(..., MobSpawnType.TRIGGERED)`, ce qui déclenche la
  finalisation vanilla du spawn et l'animation de particules.

## À faire

- **Exempter la Mother Spider du ralentissement** quand elle se déplacera : elle serait freinée par
  sa propre ponte. Vanilla fait exactement cela, `Spider.makeStuckInBlock` ignore l'effet pour la
  toile et rien d'autre.
- Trancher le plafond de la Fortune sur l'œuf éclos : elle peut aujourd'hui le faire dépasser une
  ficelle, alors que son tirage de base est plafonné à 1.
