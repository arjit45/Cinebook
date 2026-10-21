# Archivo

Bundled because docs/PRD.md §8 asks for one family with two jobs — SemiBold for
headings and money, Regular for everything else — and a lab machine that cannot be
trusted to have it installed.

- `Archivo-Regular.ttf`, `Archivo-SemiBold.ttf` — static instances at weight 400 and 600,
  width axis 100.
- `OFL.txt` — the SIL Open Font License the family ships under. Archivo declares no
  Reserved Font Names, so the instances keep the family name.

Source: <https://github.com/google/fonts/tree/main/ofl/archivo>, file
`Archivo[wdth,wght].ttf`. The statics were cut from that variable font with
fontTools, because JavaFX applies a variable font's default instance and registered
the whole file as a family called "Archivo SemiBold" — which meant
`-fx-font-family: "Archivo"` silently fell back to the system sans:

```bash
python -m fontTools.varLib.instancer "Archivo[wdth,wght].ttf" wght=400 wdth=100 \
    -o Archivo-Regular.ttf --update-name-table
python -m fontTools.varLib.instancer "Archivo[wdth,wght].ttf" wght=600 wdth=100 \
    -o Archivo-SemiBold.ttf --update-name-table
```

The "Expanded" width the PRD names is width axis 125; it is not applied here, because
JavaFX selects faces by weight and family only. Headings use SemiBold at standard
width instead, which is the closest faithful rendering available.
