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
| Déplacement, IA, combat, sons | Néant |
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
