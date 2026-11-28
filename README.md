<p align="center">
  <img src=".github/icon.png" width="112" alt="Kav">
</p>

<h1 align="center">Kav extensions</h1>

<p align="center">
  Kav's own extensions, and examples of what an extension for Kav can do.
</p>

<p align="center">
  <img alt="Kav 5 or newer" src="https://img.shields.io/badge/Kav-5%2B-9ABEFF">
  <a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-lightgrey"></a>
</p>

<p align="center">
  <a href="https://www.buymeacoffee.com/Noamm"><img alt="Buy Me A Coffee" src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png"></a>
</p>

These are [Kav](https://github.com/ImNoammm/kav)'s own extensions, made by Kav. They are also good examples for anyone
making their own: each one is small, and shows a different thing an extension can do. Kav 5 and newer come with this
source already added (Settings, Extensions); you can remove it like any other source. Every extension comes in off:
switch on the ones you want.

[![Add to Kav](add-to-kav.svg)](https://kavapp.link/add-source?github=ImNoammm/kav-extensions)

To add it by hand: in Kav open Settings, Extensions, Sources, the plus, and enter `ImNoammm/kav-extensions`.

## The extensions

| Extension | What it shows |
|---|---|
| My limits | Route rules: hides routes with more changes or a longer walk than you set, with the reason, and tags night rides. |
| Stop reminder | A card on stop boards; one tap and a notification reminds you to head to the stop a few minutes later. |
| Time split | A line under each search result: time on board, waiting and walking. |
| Shimeji | A little bus that lives on Kav's screen: it walks along the bottom, climbs the sides, hangs from the top, and sits and walks on cards. It looks around, naps when left alone and hops when you tap it; pick it up and throw it, and it falls, bounces and lands. Drawn by the extension itself in Kav's colors. It keeps only the places of the screen's pieces, never the picture. |
| Gravity | Shake the phone and Kav's screen comes loose: its buttons, rows and texts fall, bounce and slide as you tilt the phone, then fly back to their places after a few seconds, a tap or another shake. It uses Kav's screen picture, the same screen without its pieces, and the pieces' boxes, never what you type. |

All five are Kotlin. Each one has settings, on its page in Settings, Extensions. Problems go to this repository's issues.

## Themes

A theme changes how all of Kav looks: its colors in the light, dark and black screen looks, its corners, its type, the
outline drawn around cards and buttons, and the colors of Kav's map. Pick one in Settings, Look and language. A theme
has no code, only its `manifest.json` and, for most, a font; Windows XP also has pictures it draws some of Kav's parts
with.

| Theme | What it looks like |
|---|---|
| Windows XP | Windows XP as it was: Luna's blue title bars, the green Start button on the blue taskbar, its push buttons and windows, Tahoma, and Bliss behind it. One look, the same in light and dark. |
| Scribble | Kav drawn by hand: pencil outlines that never quite close, handwriting, cream paper and a yellow highlighter. Dark is a chalkboard. |
| Cherry blossom | Soft pinks, round corners and a rounded type. Petal pink by day, a night under the blossoms in the dark. |
| Gruvbox | Gruvbox's own warm colors, in its dark and light versions and the hard dark one for black screens. |
| Terminal | Kav as a text terminal: monospace type, square corners, boxes in double lines and green on black. The black screen look is amber. |

The fonts come with the themes, each under its own license, which is in the theme's `fonts` folder (Windows XP's has a
note instead):

| Theme | Font | License |
|---|---|---|
| Scribble | Playpen Sans Hebrew, by TypeTogether | [SIL Open Font License 1.1](https://openfontlicense.org) |
| Cherry blossom | Varela Round | [SIL Open Font License 1.1](https://openfontlicense.org) |
| Terminal | Liberation Mono | [SIL Open Font License 1.1](https://openfontlicense.org) |
| Windows XP | Tahoma | Microsoft's own font, not a free one |

Gruvbox uses Kav's own type. Its colors are from [Gruvbox](https://github.com/morhetz/gruvbox) by Pavel Pertsev, under
the MIT license. Windows XP's pictures are cut from Windows XP's own Luna style, and its background is Bliss, the
photograph by Charles O'Rear that Windows XP came with; both are Microsoft's. The fonts make the bundle about 1.1 MB
larger; Tahoma and Scribble's handwriting are most of that.

## For developers

Each extension is a folder under `extensions/`: its `manifest.json` and its Kotlin under `src/`. They are built with
the Kav extension kit (`kavx`) into one bundle, `kav-extensions.kavb`, which each GitHub Release of this repository
carries. Kav reads the latest release's bundle.

### Releasing

Raise `release` and `version` in the manifest of each extension that changed and commit, then build the bundle and
attach it to a new release:

```
kavx bundle . --name "Kav extensions" --icon .github/source-icon.png --out kav-extensions.kavb
kavx verify kav-extensions.kavb
gh release create v2 kav-extensions.kavb
```

## License

GPL-3.0. See [LICENSE](LICENSE).
