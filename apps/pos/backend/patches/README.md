# Isolated source patches

These patches preserve only `tools/backend/source-ownership.json` paths from
the ignored source checkouts. Exact base revisions and every resulting file
SHA are in `manifest.json`. The exporter checks forward application against the
pin using an isolated temporary Git index; it never resets the shared checkout,
stages its real index, commits, or contacts a remote.

On **clean, exact-pin** checkouts only (the current workspace already contains
these changes), from the project root:

```sh
git -C .workspace/sources/main-next apply --check "$PWD/backend/patches/main-next.patch"
git -C .workspace/sources/viki-go apply --check "$PWD/backend/patches/viki-go.patch"
# Apply only after inspecting the patch and independently reviewing the change:
git -C .workspace/sources/main-next apply "$PWD/backend/patches/main-next.patch"
git -C .workspace/sources/viki-go apply "$PWD/backend/patches/viki-go.patch"
tools/backend/verify.sh
```

No original YAML or generated `internal/document/*.go` edit is in these patches.
The new canonical YAML and isolated generated outputs are separate tracked
backend artifacts. Applying a patch does not install indexes, enable an API,
or establish runtime security/durability acceptance.
