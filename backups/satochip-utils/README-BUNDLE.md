# Satochip-Utils Backup Bundle

This repository stores an offline-restorable git bundle backup of:

- Upstream: https://github.com/Toporin/Satochip-Utils
- Snapshot date: 2026-03-03
- Bundle file: `Satochip-Utils-backup-20260303.bundle`
- SHA256: `a6799fe9a19d2c297954c974fcfddaa8f3efa0e2f5786015638037065a56c9b6`

## Restore commands

```bash
# Option A: clone directly from bundle
git clone Satochip-Utils-backup-20260303.bundle Satochip-Utils-restore

# Option B: inside an existing repo
mkdir Satochip-Utils-restore && cd Satochip-Utils-restore
git init
git remote add backup-bundle ../Satochip-Utils-backup-20260303.bundle
git fetch backup-bundle 'refs/*:refs/*'
```

## Notes

- This backup preserves full git history and refs present at snapshot time.
- If upstream disappears, this bundle can still recreate the repository.
