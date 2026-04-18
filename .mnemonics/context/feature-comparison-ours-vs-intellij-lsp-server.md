---
id: 8dbb929e-bad8-4971-b6ea-03eaa3732c22
created: '2026-04-18T12:05:06.153Z'
modified: '2026-04-18T12:05:06.153Z'
memory_type: context
tags: []
---
## Reference Server (Ruin0x11) - Feature List

### Document Commands (14 total)
1. FindDefinitionCommand ✅ (ours has)
2. FindUsagesCommand ✅ (ours has, but broken in SDK 2026.1)
3. FindImplementationCommand ✅ (ours has)
4. FindTypeDefinitionCommand ✅ (ours has)
5. DocumentSymbolCommand ✅ (ours has)
6. HoverCommand ✅ (ours has)
7. CompletionCommand ✅ (ours has)
8. CompletionItemResolveCommand ✅ (ours has)
9. DocumentHighlightCommand ✅ (ours has)
10. DocumentFormattingCommand ✅ (ours has)
11. CodeLensCommand ❌ (ours is MISSING - shows run markers)
12. DiagnosticsCommand - via DiagnosticsThread

### Project Commands (7 total)
13. WorkspaceSymbolCommand ✅ (ours has)
14. RunConfigurationsCommand ❌ (MISSING)
15. OpenRunConfigurationsCommand ❌ (MISSING)
16. RunProjectCommand ❌ (MISSING)
17. BuildProjectCommand ❌ (MISSING)
18. OpenProjectStructureCommand ❌ (MISSING)
19. ToggleFrameVisibilityCommand ❌ (MISSING)

## Missing Features in Our Server

### High Priority
- **CodeLensCommand**: Shows run/test markers in editor gutter with clickable "Run" actions

### Medium Priority  
- **RunProjectCommand**: Execute run configurations from LSP
- **RunConfigurationsCommand**: List available run configurations
- **BuildProjectCommand**: Build project from LSP

### Low Priority
- **OpenProjectStructureCommand**: Open project tool window
- **ToggleFrameVisibilityCommand**: Toggle tool window visibility
