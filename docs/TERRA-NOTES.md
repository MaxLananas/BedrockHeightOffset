# Notes d'analyse — l'écosystème Terra et la hauteur de monde (BTE)

Analyse du 21/09/2026 sur les dépôts suivants (clonés depuis GitHub) :

| Dépôt | Quoi | Rapport avec le problème |
|---|---|---|
| `BuildTheEarth/terraplusplus` (TerraPlusPlus, « T++ ») | Fork Terra121 orienté perfs — le mod qui génère la Terre réelle dans Minecraft | Comment la génération Java place le relief réel dans les Y Minecraft |
| `BTE-Germany/TerraPlusMinus` (« T+- ») | Plugin **Bukkit** de génération BTE (utilisé par les serveurs BTE en 1.21) | Vrai serveur BTE : génération + commandes builders (`/tpll`) |
| `BuildTheUK/terraminusminus` (« T-- ») | Fork de TerraMinusMinus (lignée Terra121) | Même modèle de hauteur que les deux autres (lignée commune) |

## La découverte clé : même Terra **clamp** au plafond du monde

Dans `TerraPlusMinus`, `gen/RealWorldGenerator.java` (l. 122-135) :

```java
int minWorldY = worldInfo.getMinHeight();
int maxWorldY = worldInfo.getMaxHeight();
...
int groundHeight = min(terraData.groundHeight(x, z) + this.yOffset, maxWorldY - 1);
int waterHeight = min(terraData.waterHeight(x, z) + this.yOffset, maxWorldY - 1);
```

Le modèle BTE en Java est : **Y_minecraft = altitude_géographique + `yOffset`**, le tout **clampé à
`maxWorldY - 1`**. Autrement dit :

1. Le relief réel (Everest = 8840 m, mais surtout tout ce qui dépasse le plafond de la dimension)
   est **écrasé** au sommet du monde quand il dépasse `maxWorldY`. Les projets Terra n'ont aucun
   moyen magique de dépasser la hauteur de monde : ils subissent le même mur (`minHeight`/`maxHeight`
   de la dimension, fournis par le datapack/modpack serveur).
2. Pour « construire aussi haut », la communauté Java ne dépasse donc pas le plafond : elle **le
   déplace** (monde/datapack à hauteur étendue, ex. dimension allant jusqu'à Y≈1984) et/ou descend
   le `yOffset` pour faire rentrer le relief dans la fenêtre disponible.

## Ce que ça valide pour SkyWindow

- Les maths extrêmes de SkyWindow (reslice en 126+ sections, offset ~1400-1888, Y effectifs jusqu'à
  ≈1952) sont dans la **même enveloppe** que les mondes BTE Java « hauteur étendue » eux-mêmes. Le
  modèle fenêtre de SkyWindow est l'équivalent *par joueur* de ce que les serveurs BTE font *par
  monde* : un décalage Y constant entre deux espaces de coordonnées, avec conversion à la frontière.
- La conversion `/tpll` (lat/lon/hauteur → coordonnées MC via `projection.fromGeo` + hauteur) est
  l'analogue exacte de `/skywindow explain` : un outil de traduction d'espaces pour les builders.
- Le `clamp` de Terra est la preuve par l'exemple qu'**aucun code serveur** ne peut empêcher le mur
  `maxWorldY` — donc la seule façon pour un joueur Bedrock de *voir et d'éditer* au-delà de la
  fenêtre négociée Bedrock (−512..512, soit tout serveur BTE Java « haute altitude ») est
  précisément la fenêtre glissante côté protocole. C'est la thèse de SkyWindow, confirmée par
  l'écosystème lui-même.

## Conséquence produit (faite dans cette session)

Le builder BTE travaille en **coordonnées d'affichage** partout — commandes `/tp`, `/setblock`,
`/fill`, blocs commande, etc. SkyWindow traduit l'espace d'affichage → espace réel (+O) à
l'entrée du serveur, et inversement à la sortie. Les commandes des plugins BTE (type `tpll` avec
positions) s'enseignent via `command-position-schemas` dans `config.properties`.
