# DESIGN — Cauchemar

Document de conception vivant. Il décrit ce que le mod **est** aujourd'hui et ce qu'il **vise**.
Toute feature qui arrive dans le code arrive aussi ici, dans la même PR. Si ce n'est pas écrit ici,
ce n'est pas dans le mod.

Il se lit du point de vue du joueur et reste au niveau de l'intention : ce qu'une feature apporte,
pas comment elle est faite. Le détail d'implémentation vit dans `docs/`, une page par feature, et ce
document y renvoie.

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
| Blocs `cauchemar:spider_egg` et `spider_egg_hatched` | En place et complets |
| Araignées en particules autour des pontes | En place |
| Déplacement, IA, combat | Néant |
| Spawn naturel | Néant, l'entité n'apparaît que par `/summon` |

## Features

Une section par feature livrée, ajoutée dans la PR qui la livre. Quelques lignes chacune : à quoi
elle sert, ce qu'elle change pour le joueur, ce à quoi elle se rattache. Les valeurs, les classes,
les pièges du moteur et les décisions écartées vont dans la page `docs/` de la feature, pas ici.

### Mother Spider (entité)

La créature centrale du mod. Grande araignée aux longues pattes, pensée pour les grottes.

Elle existe et s'affiche, mais ne fait encore rien : ni déplacement, ni détection, ni combat. Tout
cela est à concevoir, et l'échec de la première itération invite à y aller par étapes.

### Œufs d'araignée (blocs)

La trace qui précède la bête, et la matière première du nid à venir. Le joueur les rencontre avant
la créature elle-même : c'est ce qui doit lui faire comprendre où il met les pieds.

Une ponte enveloppée de soie, dans deux états : **intacte**, encore pleine, et **éclose**, affaissée
et vidée. Ce qu'elles changent pour le joueur :

- elles **gênent le passage** sans jamais le condamner : on y traîne des pieds, on s'y accroche, mais
  une lame en vient vite à bout, et la main y arrive à la longue ;
- elles **brûlent** et cèdent aux explosions, ce qui laisse au joueur des façons brutales de dégager
  un nid, avec le bruit et la lumière que cela suppose ;
- elles **rapportent de la ficelle**, un peu plus quand la ponte est encore pleine ;
- **déchirer une ponte intacte réveille parfois ce qui dort dedans** : une chance sur sept environ de
  libérer une araignée, rarement une araignée des cavernes. Récolter l'œuf entier, aux cisailles ou
  au Toucher de Soie, n'éveille rien.

Détail d'implémentation : [docs/spider-eggs.md](docs/spider-eggs.md).

### Ce qui grouille autour (particules)

Une ponte intacte n'est jamais tout à fait immobile : une ou deux petites araignées rôdent en
permanence autour d'elle, marchent sur le sol, grimpent aux murs, passent au plafond, s'arrêtent et
repartent. Elles ne s'éloignent jamais beaucoup de leur ponte, et disparaissent peu après elle.

C'est le premier signal que le joueur reçoit, avant même de distinguer la ponte : quelque chose bouge
au bord du champ de vision. Il peut les écraser en marchant dessus, mais tant que la ponte est là,
elle en refait.

Déchirer une ponte en libère parfois **une nuée** qui détale dans toutes les directions avant de se
perdre dans le noir. C'est un effet, pas une menace : ces araignées-là ne sont que du décor.

Détail d'implémentation : [docs/spider-particles.md](docs/spider-particles.md).

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
| Documentation | ce fichier pour l'intention, `docs/<feature>.md` pour l'implémentation |
| Assets entité | `geo/entity/<id>.geo.json`, `textures/entity/<id>.png`, `animations/entity/<id>.animation.json` |
| Assets bloc | `blockstates/<id>.json`, `models/block/<id>.json`, `models/item/<id>.json`, `textures/block/<id>.png`, `textures/item/<id>.png` si icône dédiée, `data/cauchemar/loot_table/blocks/<id>.json` |
| Assets son | `sounds/<catégorie>/<id>/<nom><n>.ogg`, déclarés dans `sounds.json`. **OGG Vorbis et mono obligatoires** : l'Opus n'est pas lu, et un fichier stéréo n'est pas spatialisé (même volume partout dans le monde) |

Organisation du code Java :

```
com.payangar.cauchemar
  Cauchemar.java          point d'entrée commun, branche les registries
  CauchemarClient.java    point d'entrée client, branche les renderers
  Config.java             ModConfigSpec NeoForge
  registry/               un fichier par type de registre (ModBlocks, ModItems, ModEntities, ModSounds)
  block/                  les blocs
  entity/                 les entités
  client/                 rendu, modèles GeckoLib
```
