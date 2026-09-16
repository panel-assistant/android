# Profile editor toolchain

This directory builds the vendored CodeMirror runtime used by the panel Profile editor. Android and Gradle builds consume the committed `app/src/main/assets/vendor/profile-editor/codemirror.js` bundle and do not run npm or download packages.

## Rebuild and verify

Use Node.js 22.23.2 and npm 10.9.8. The direct dependencies are exact pins and `package-lock.json` records registry URLs plus sha512 integrity for the complete dependency graph. The supported clean install deliberately disables package lifecycle scripts; esbuild loads its integrity-locked platform package directly, and the verification step executes esbuild so an unsupported or incomplete installation fails closed.

```bash
cd tools/profile-editor
npm ci --ignore-scripts --audit=false --fund=false
npm run build
npm run check
npm run sbom
```

`npm run check` validates direct pins, every lockfile package integrity, the exact runtime package inventory in `NOTICE.txt`, the collected upstream MIT copyright notices in `LICENSE.txt`, a byte-for-byte rebuild of the committed browser bundle, and reproducible generation of its runtime dependency inventory. `npm run sbom` writes that inventory as CycloneDX 1.6 JSON to `build/profile-editor-runtime.cdx.json`; it describes the integrity-locked npm runtime graph used to build the embedded CodeMirror asset and deliberately excludes esbuild and other build-only packages. When dependencies change, review the new lockfile graph and upstream license files before rebuilding and committing `package.json`, `package-lock.json`, `LICENSE.txt`, `NOTICE.txt`, and `codemirror.js` together.
