# Data Flow Analysis - PRD

## 1. Overview

Data Flow Analysis tracks how values propagate through program code, answering questions like:
- **DATA-flow-from**: Where do the values FROM this point propagate TO? (forward data flow / outgoing)
- **DATA-flow-to**: Where do the values AT this point come FROM? (backward data flow / incoming)

This is distinct from Control Flow (Call Hierarchy), which tracks function call relationships rather than value propagation.

Note: These semantics align with IntelliJ IDEA's UI:
- "Data Flow From Here" = outgoing flow (values from selected element)
- "Data Flow To Here" = incoming flow (values to selected element)

## 2. LSP Protocol Analysis

### 2.1 Current LSP 3.17 Support
The Language Server Protocol specification version 3.17 contains **no standardized support** for data flow analysis. There are no:
- Specific methods for data flow queries
- Data flow related capabilities in server/client capability negotiation
- Data flow related JSON structures or parameter types
- Data flow related notifications or requests

### 2.2 Extension Mechanism
LSP supports custom extensions through vendor-specific methods. Examples from existing implementations:
- Kotlin Language Server uses `kotlin/*` namespace for custom methods
- Methods are registered under `@JsonSegment("kotlin")` annotation
- Custom methods follow JSON-RPC structure with specific request/response types

## 3. IntelliJ IDEA Data Flow Capabilities

### 3.1 Core Architecture
IntelliJ provides data flow analysis through its slicer framework:
- **SliceAnalysisParams**: Controls data flow direction with `dataFlowToThis` boolean flag
- **DataFlowIRProvider**: Converts PSI elements to data flow intermediate representation
- **KtControlFlowBuilder**: Builds control flow graphs for data flow analysis
- **SlicePanel/RootNode**: Displays data flow results in IDE UI
- **KotlinSliceUsage**: Wraps PSI elements with analysis parameters for slicing

### 3.2 Key Classes & Methods (from IntelliJ 2026.1)
- `SliceAnalysisParams.dataFlowToThis`: When true, analyzes inflow (to here); when false, analyzes outflow (from here)
- `DataFlowIRProvider.createControlFlow(factory, psiBlock)`: Creates control flow from PSI block
- `KtControlFlowBuilder`: Handles Kotlin-specific control flow construction including lambdas
- `SliceRootNode(project, duplicateMap, KotlinSliceUsage(element, params))`: Root for slice analysis
- `KotlinUsageContextDataFlowPanelBase`: Base class showing UI integration

### 3.3 Usage Pattern (from KotlinUsageContextDataFlowPanelBase.kt)
1. Prepare analysis parameters with `dataFlowToThis = isInflow` flag
   - `isInflow = true` → Data Flow To Here (incoming/backward)
   - `isInflow = false` → Data Flow From Here (outgoing/forward)
2. Build control flow IR from PSI element using `SliceRootNode`
3. Display results in `SlicePanel` within IDE tool window

## 4. Feasibility Assessment

### 4.1 Technical Feasibility
**HIGH** - Implementation is feasible because:
- IntelliJ already provides all necessary data flow analysis APIs
- LSP extension mechanism allows custom methods
- Similar features (call hierarchy) already implemented in this project
- Existing control flow implementation provides foundation for extension
- Clear separation between UI layer (UsageContextPanel) and analysis layer (SliceAnalysisParams)

### 4.2 Protocol Design Options
**Option 1: Extend Call Hierarchy** (Not Recommended)
- Misuses existing protocol for different semantics
- Would confuse clients expecting call hierarchy behavior

**Option 2: New Data Flow Methods** (Recommended)
- Follow LSP extension pattern with custom namespace
- Clear separation from existing features
- Explicit data flow semantics matching IntelliJ terminology

**Option 3: Enhance Existing Methods** (Limited Applicability)
- Could add data flow flags to existing methods like references
- Less expressive than dedicated methods

## 5. Recommended Implementation

### 5.1 LSP Extension Approach
Following kotlin-language-server pattern:
- Custom methods under `dataflow/` namespace
- Clear method names indicating direction
- Reuse existing LSP data structures where applicable

### 5.2 Proposed Methods

#### 5.2.1 textDocument/dataflowFrom
**Purpose**: Get outgoing data flow - where values FROM this position propagate TO
**Params**: TextDocumentPositionParams + WorkDoneProgressParams
**Result**: DataFlowLocation[] | null
**Semantics**: Given a position, return locations that can be reached by values from this position
**Corresponds to**: IntelliJ's "Data Flow From Here" (outgoing/forward flow)

#### 5.2.2 textDocument/dataflowTo
**Purpose**: Get incoming data flow - where values AT this position come FROM
**Params**: TextDocumentPositionParams + WorkDoneProgressParams
**Result**: DataFlowLocation[] | null
**Semantics**: Given a position, return locations whose values can reach this position
**Corresponds to**: IntelliJ's "Data Flow To Here" (incoming/backward flow)

### 5.3 Data Flow Location Structure
Reuse and adapt from Call Hierarchy patterns:
```
interface DataFlowLocation {
    /**
     * The location involved in the data flow.
     */
    location: Location;
    
    /**
     * The range at which the data flow appears.
     */
    range: Range[];
    
    /**
     * A data entry field preserved between requests.
     */
    data?: LSPAny;
}
```

### 5.4 Implementation Architecture
Following existing patterns in IdeaLS (same as control flow):
```
Client Request
    ↓
MyTextDocumentService.java (LSP4J endpoint)
    ↓
DataFlowFromCommand.java / DataFlowToCommand.java (extends LspCommand)
    ↓
IntelliJ PSI APIs + SliceAnalysisParams
    ↓
Response (DataFlowLocation[])
```

### 5.5 Key Implementation Details
Based on `KotlinUsageContextDataFlowPanelBase.kt`:
- **command flow**: LSP request → MyTextDocumentService → DataFlow*Command
- **parameter mapping**: Convert LSP Position to PSI element offset
- **analysis setup**: Create `SliceAnalysisParams` with correct `dataFlowToThis` flag
  - For `dataflowFrom`: `dataFlowToThis = false` (outgoing)
  - For `dataflowTo`: `dataFlowToThis = true` (incoming)
- **element resolution**: Find PSI element at position (similar to prepareCallHierarchy)
- **result conversion**: Transform SlicePanel results to DataFlowLocation format

## 6. Implementation Plan

### 6.1 Registration
- Add data flow server capabilities in LspServer.java
- Register custom methods under dataflow/ namespace

### 6.2 Service Modifications
- Add dataflowFrom() and dataflowTo() methods to MyTextDocumentService.java
- Follow existing pattern from definition()/references() methods
- Handle project null checks and error cases consistently

### 6.3 Command Implementation
- Create DataFlowFromCommand.java and DataFlowToCommand.java extending LspCommand
- Implement using SliceAnalysisParams with appropriate dataFlowToThis flag:
  * DataFlowFromCommand: dataFlowToThis = false
  * DataFlowToCommand: dataFlowToThis = true
- Use existing infrastructure:
  * EditorUtil.withEditor() for PSI access
  * SliceRootNode(project, DuplicateMap(), KotlinSliceUsage(element, params))
  * DataFlowIRProvider and KtControlFlowBuilder (via SliceAnalysisParams flow)
- Convert slice results to DataFlowLocation array

### 6.4 Testing
- Add test cases to test_lsp_comprehensive.py
- Create test data with known data flow relationships (variable assignments, field accesses, etc.)
- Verify both inflow (dataflowTo) and outflow (dataflowFrom) directions
- Test cross-file data flow scenarios
- Validate against IntelliJ UI behavior where possible

## 7. Dependencies & Risks

### 7.1 Dependencies
- Existing control flow implementation provides architectural foundation
- IntelliJ data flow APIs (SliceAnalysisParams, DataFlowIRProvider) are stable
- LSP extension mechanism is proven and safe (per kotlin-language-server)
- Reuses existing PSI element resolution and async execution patterns

### 7.2 Risks
- **Low**: Custom method conflicts (mitigated by unique dataflow/ namespace)
- **Medium**: Performance impact of data flow analysis (mitigated by async execution, same as existing features)
- **Low**: Client adoption (custom methods require client support, but can be detected/gated)
- **Low**: Semantic mismatch (mitigated by clear documentation and IntelliJ alignment)

## 8. Open Questions

### 8.1 Granularity
Should data flow be expression-level, statement-level, or variable-level?
- Recommend starting with expression-level for precision (matches IntelliJ slicer behavior)

### 8.2 Complex Values
How to handle complex data structures and objects?
- Recommend tracking reference flow rather than deep value analysis initially
- Can be enhanced later with more sophisticated data flow facts if needed

### 8.3 Result Format
Should we include additional metadata in DataFlowLocation?
- Start with minimal viable structure (location, ranges, data)
- Extend based on client needs and actual usage patterns

### 8.4 Progress Reporting
Should we support work done progress for long-running analyses?
- Yes, follow existing pattern with WorkDoneProgressParams in method signatures
- Implement slicer progress reporting where available

## 9. Integration Points with Existing Codebase

### 9.1 Reusable Components
- LspServer.java: Add capability registration
- MyTextDocumentService.java: Add endpoint methods (follow definition/references pattern)
- Command pattern: Extend existing LspCommand implementations
- PSI resolution: Reuse element-finding logic from prepareCallHierarchy
- Async execution: Use same project/threading patterns as other LspCommands

### 9.2 Files to Create/Modify
- **New Files**:
  - server/src/main/java/org/rri/ideals/server/dataflow/DataFlowFromCommand.java
  - server/src/main/java/org/rri/ideals/server/dataflow/DataFlowToCommand.java
  - server/src/main/java/org/rri/ideals/server/dataflow/DataFlowUtil.java (shared helpers)

- **Files to Modify**:
  - server/src/main/java/org/rri/ideals/server/MyTextDocumentService.java - Add endpoints
  - server/src/main/java/org/rri/ideals/server/LspServer.java - Register capability
  - server/src/test/java/... - Add unit tests (if following existing test patterns)

### 9.3 Implementation Strategy
1. Implement DataFlowToCommand first (incoming/backward flow)
2. Implement DataFlowFromCommand (outgoing/forward flow) - similar but flag inverted
3. Add LSP endpoints to MyTextDocumentService
4. Register capabilities in LspServer
5. Add test cases
6. Verify against IntelliJ behavior where test oracles available

---
*Documented based on research of:*
- *LSP 3.17 specification*
- *IntelliJ IDEA 2026.1 slicer APIs (KotlinUsageContextDataFlowPanelBase.kt, SliceAnalysisParams.kt)*
- *Existing control flow implementation in this project*
- *kotlin-language-server extension pattern*