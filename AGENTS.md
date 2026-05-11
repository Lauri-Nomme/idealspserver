# Agent Guidelines for IdeaLS

## Key Files
- `iteration.md` - Development and testing workflow
- `scripts/test_lsp_comprehensive.py` - Main test suite
- `scripts/shell/install-plugin.sh` - Build and install plugin

## Always Read First
Before each session, read `iteration.md` to understand current workflow and any new conventions.

## Logging
- **ALWAYS use `LOG.warn()`** for logging - never `LOG.info()`. This ensures logs are visible in production.
- Check logs with: `journalctl --user -u idealsp.service --no-pager | grep -iE '(error|warn)'`

## Building and Testing
```bash
# Build and install plugin
bash scripts/shell/install-plugin.sh

# Run tests
python3 scripts/test_lsp_comprehensive.py
```

## Service Management
```bash
# Restart service
systemctl --user restart idealsp.service

# Check status
systemctl --user status idealsp.service
```