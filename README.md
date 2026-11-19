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
source already added (Settings, Extensions); you can remove it like any other source.

[![Add to Kav](add-to-kav.svg)](https://kavapp.link/add-source?github=ImNoammm/kav-extensions&name=Kav%20extensions)

To add it by hand: in Kav open Settings, Extensions, Sources, Add, and enter `github.com/ImNoammm/kav-extensions`.
Kav shows this key fingerprint; it should be the same:

```
979ca32d8bc1dda15c1a21d9d63bd465
```

## The extensions

| Extension | Written in | What it shows |
|---|---|---|
| Map style | Kotlin | Kav's stops on the Live tab drawn as pins, squares or dots in a colour you pick, and a pin for a place of your own. Redraws the moment a setting changes. |
| My limits | Kotlin | Route rules: hides routes with more changes or a longer walk than you set, with the reason, and tags night rides and routes with short walks. |
| Stop reminder | Kotlin | A card on stop boards; one tap and a notification reminds you to head to the stop a few minutes later. |
| Notebook | Kotlin | A screen of its own that you can show as a tab, built from Kav's own components. |
| My stop | JavaScript | A Home card with the next departures from a stop you choose. |
| Time split | JavaScript | A line under each search result: time on board, waiting and walking. |

Each one has settings, on its page in Settings, Extensions. Problems go to this repository's issues.

## For developers

Each extension is a folder under `extensions/`: its `manifest.json`, and its Kotlin under `src/` or its script in
`main.js`. They are built with the Kav extension kit (`kavx`). `kav-repository.json`, `index.json`, `releases.json` and
`packages/` are the source Kav reads; `kavx` writes them.

### Releasing

Raise `release` and `version` in the extension's manifest, then on the computer that holds the keys:

```
kavx pack extensions/<name> --key examples-source.pem --out dist
kavx repo add . dist/<name>-<release>.kavx --changelog "What's new"
kavx repo sign . --index-key examples-index.pem
kavx repo verify .
```

Commit `index.json`, `releases.json` and the new file in `packages/`. Never pack a published release again.

### Keeping the index fresh

Kav stops installing from a source whose index has expired. `.github/workflows/release.yml` signs it again every
Monday with the `KAV_INDEX_KEY` secret. By hand: `kavx repo sign . --index-key examples-index.pem`, then commit
`index.json`. `kavx repo status .` says when the index and the root expire.

## License

GPL-3.0. See [LICENSE](LICENSE).
