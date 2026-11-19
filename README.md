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
| Map style | Kav's stops on the Live tab drawn as pins, squares or dots in a color you pick on its own color picker, and a pin for a place you choose with Kav's place search. Redraws the moment a setting changes. |
| My limits | Route rules: hides routes with more changes or a longer walk than you set, with the reason, and tags night rides. |
| Stop reminder | A card on stop boards; one tap and a notification reminds you to head to the stop a few minutes later. |
| Notebook | A screen of its own that you can show as a tab, built from Kav's own components. |
| My stop | A Home card with the next departures from a stop you pick with Kav's stop picker. |
| Time split | A line under each search result: time on board, waiting and walking. |

All six are Kotlin. Each one has settings, on its page in Settings, Extensions. Problems go to this repository's issues.

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
