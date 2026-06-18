---
name: CONSOLIDATED-AGENT-INSTRUCTIONS
description: Complete consolidated agent instructions + all former .github/instructions/ content merged into single unified structure.
---

# Agent Instructions — Consolidated & Complete

**FULL CONSOLIDATION:** All content from `.github/instructions/` + `.github/agents/` merged here.
- Former `.github/instructions/` folder: DELETED (all content merged)
- Former `.github/agents/` folder: CONSOLIDATE + EXPANDED
- Total: 4400+ lines of guidance now organized, compressed, and DRY

**Navigate by:** [Quick Checklist for Code Review](#quick-checklist-for-code-review) | [Quick Checklist for Security](#quick-checklist-for-security)

---

## 📋 Consolidated Standards (Read These First)

### [SHARED-RULES.md](SHARED-RULES.md) — 319 lines
**Cross-cutting non-negotiable rules all agents follow.**

Contains:
- Two Instruction Systems (IDE vs runtime)
- 14 Non-Negotiable Constraints
- Java 25 + Spring Boot 4 patterns
- DI + logging + Kafka + Redis + error handling
- Testing — zero Mockito + real doubles
- Shell script safety + credential handling
- MCP tool declaration

**Used by:** All agents reference this. Coder checks when stuck, CodeReviewer when auditing.

---

### [CHECKLISTS.md](CHECKLISTS.md) — 332 lines
**Actionable checklists for code review and security audit.**

#### CodeReviewer Checklist (12 sections, ~150 lines)
1. Java 25 Idioms (10 checks)
2. Spring Boot 4 DI (7 checks)
3. Bean Lifecycle & Conditionals (5 checks)
4. Logging (5 checks)
5. Error Handling (6 checks)
6. Kafka (7 checks)
7. Redis (6 checks)
8. Testing — Zero Mockito (8 checks)
9. API Design (6 checks)
10. Configuration & Secrets (5 checks)
11. Performance & Concurrency (5 checks)
12. Code Maintainability (5 checks)

#### Security Audit Checklist (10 sections, ~150 lines)
1. Credential & Secret Safety (9 checks)
2. API Authentication (4 checks)
3. Input Validation & Size Limits (4 checks)
4. Actuator Exposure (1 check)
5. Error Response Sanitisation (3 checks)
6. Dependency & CVE Hygiene (3 checks)
7. ProcessBuilder / Command Execution (4 checks)
8. Redis / Kafka Data Security (3 checks)
9. Secure Headers (1 check)
10. Temp File Handling (2 checks)

Plus: **Shell Script Security** (4 explicit checks)

#### Severity Levels
- CodeReviewer: BLOCKER | MAJOR | MINOR | INFO
- Security: CRITICAL | HIGH | MEDIUM | LOW

**Used by:** CodeReviewer runs all 12 sections. Security runs all 10 + shell section.

---

### [GenericCodingPractices.md](GenericCodingPractices.md) — 248 lines
**Language-agnostic best practices all coders follow.**

Contains:
- Before coding (read code, check `common/`, test after changes)
- Logging rules (`[ClassName]` prefix, exception as last arg)
- Error handling (no silent catches, generic API responses)
- Testing (zero mocking, real test doubles)
- DI patterns (constructor-based)
- Credential safety (env vars only)
- Shell script safety (guard pattern)
- API/HTTP response rules
- Immutability & data structures
- Naming conventions + code organization
- Forbidden patterns (all languages)

**Used by:** JavaCoder + PythonCoder reference this for fundamentals.

---

## 🎭 Language-Specific Coders

### [JAVA-CODER.md](JAVA-CODER.md) — 450+ lines (NEW: CONSOLIDATED)
**Java 25 + Spring Boot 4 implementation.**

Merges:
- JavaCoder.agent.md (base)
- instructions/java/coding-conventions.md (Java 25 patterns)
- instructions/java/error-handling.md (Java error patterns)
- instructions/java/logging.md (Logging conventions)
- instructions/spring/dependency-injection.md (Spring DI)
- instructions/spring/kafka.md (Kafka patterns)
- instructions/spring/redis.md (Redis patterns)

Contains:
- Java 25 patterns (records, sealed, pattern matching, virtual threads, text blocks)
- Spring Boot 4 (constructor DI, conditional beans, MCP tools)
- Kafka producer/consumer patterns
- Redis `StringRedisTemplate` patterns
- String constants + package structure
- Spring configuration rules
- Testing (JUnit 5 + zero Mockito)
- Java-specific forbidden patterns

**Read this if:** Implementing Java/Spring Boot code.

---

### [PythonCoder.agent.md](PythonCoder.agent.md) — 282 lines
**Python 3.10+ + FastAPI implementation (iWiki RAG services).**

Contains:
- Python 3.10+ patterns (type hints, pattern matching, async/await, dataclasses, f-strings)
- FastAPI patterns (endpoints, DI, error handling)  
- Testing (pytest, real doubles, no mock.patch)
- Python-specific forbidden patterns

**Read this if:** Implementing Python code (iWiki services).

---

### [MCP-SERVERS.md](MCP-SERVERS.md) — 250+ lines (NEW: CONSOLIDATED)
**Spring AI MCP Server tool authoring.**

Merges:
- instructions/mcp/server-setup.md
- instructions/mcp/tool-authoring.md

Contains:
- Tool declaration patterns
- Spring AI MCP Server setup
- Tool description + parameter format
- Registration in `@Configuration`
- Tool locations per service
- Common tools (Strategy, Codegen, Feedback)
- Logging requirements
- Error handling for tools
- Testing tools

**Read this if:** Declaring or auditing MCP `@Tool` methods.

---

### [TESTING-STANDARDS.md](TESTING-STANDARDS.md) — 350+ lines (NEW: CONSOLIDATED)
**Zero Mockito policy + real test doubles.**

Consolidates:
- instructions/testing/zero-mock-policy.md (full)
- Testing patterns from all languages

Contains:
- Hard rule: no Mockito anywhere
- Real test double pattern (inner `static` classes)
- JUnit 5 conventions
- Test naming (`method_givenCondition_expectedOutcome`)
- AssertJ assertions
- No Spring context in unit tests
- Awaitility (never `Thread.sleep()`)
- Capturing test doubles
- Test data builders
- Parameterized tests
- Integration test setup
- Validation checklist

**Read this if:** Writing or reviewing tests.

---

## 🎭 Agent Instructions (Role-Specific Workflows)

### [Conductor.agent.md](Conductor.agent.md) — 258 lines
**Orchestrate delivery. Pipeline: INTAKE → TESTING → FIXING → DONE (11 stages + 2 gates).**

- Full workflow + stage instructions
- Delegation templates for every agent
- Status file schema
- Module reference
- Integration points (when agents run, what blocks what)

**Read if:** Understanding full orchestration or troubleshooting stuck stage.

---

### [CodeReviewer.agent.md](CodeReviewer.agent.md) — 385 lines
**Audit code. References [CHECKLISTS.md](CHECKLISTS.md) for 12-section checklist.**

- Running checklist
- Common violations + fixes per section
- Severity levels + findings report
- Integration with Conductor

**Read if:** Reviewing code or understanding code review gates.

---

### [Security.agent.md](Security.agent.md) — 176 lines
**Audit security. References [CHECKLISTS.md](CHECKLISTS.md) for 10-section checklist.**

- Running audit checklist
- Shell script specific rules
- Severity levels + findings format
- Integration with Conductor

**Read if:** Auditing security or understanding security gates.

---

### [TestPlanner.agent.md](TestPlanner.agent.md) — 83 lines
**Convert features → JUnit 5 test scenarios (no code).**

- Test scenario planning
- Zero Mockito constraints
- Module placement paths
- Output format

**Read if:** Writing test scenarios.

---

### [Tester.agent.md](Tester.agent.md) — 71 lines
**Run + validate tests. Fix step definitions or page objects.**

- Test execution steps
- Failure interpretation
- Fix + re-run procedure
- Rules (never modify feature files to work around failures)

**Read if:** Running tests or troubleshooting test failures.

---

### [Documentation.agent.md](Documentation.agent.md) — 159 lines
**Update + maintain docs. DRY (one source of truth per concept).**

- Responsibilities
- Documentation philosophy
- Consolidation checklist
- Update patterns
- Approval criteria

**Read if:** Updating documentation.

---

## 📊 File Cross-References (What Uses What)

| Agent | References |
|-------|-----------|
| **JavaCoder** | GenericCodingPractices + SHARED-RULES + CHECKLISTS |
| **PythonCoder** | GenericCodingPractices + SHARED-RULES + CHECKLISTS |
| **CodeReviewer** | CHECKLISTS (12 sections) + SHARED-RULES |
| **Security** | CHECKLISTS (10 sections + shell script) + SHARED-RULES |
| **TestPlanner** | SHARED-RULES (testing section) |
| **Tester** | — (minimal refs) |
| **Documentation** | Project docs (README, ARCHITECTURE, .env.example) |
| **Conductor** | All agents, SHARED-RULES, CHECKLISTS, GenericCodingPractices |

---

## 🚀 For First-Time Users

1. **Understand workflow:** Read [Conductor.agent.md](Conductor.agent.md) § Standard Workflow
2. **Learn generic practices:** Read [GenericCodingPractices.md](GenericCodingPractices.md) (first 3 sections)
3. **Pick your language & role:**
   - **Java code?** → [JAVA-CODER.md](JAVA-CODER.md)
   - **Python code?** → [PythonCoder.agent.md](PythonCoder.agent.md)
   - **Reviewing code?** → [CodeReviewer.agent.md](CodeReviewer.agent.md)
   - **Auditing security?** → [Security.agent.md](Security.agent.md)
   - **Creating tests?** → [TestPlanner.agent.md](TestPlanner.agent.md)
   - **Declaring MCP tools?** → [MCP-SERVERS.md](MCP-SERVERS.md)
   - **Writing tests?** → [TESTING-STANDARDS.md](TESTING-STANDARDS.md)
4. **Reference when stuck:** [GenericCodingPractices.md](GenericCodingPractices.md) (logging, error handling, DI, testing, creds)
5. **Use for auditing:** [CHECKLISTS.md](CHECKLISTS.md) (code review + security checklists)

---

## 📋 Quick Checklist for Code Review

**Run [CHECKLISTS.md](CHECKLISTS.md) on all changed files:**

- [ ] **Java 25:** Records for DTOs → Sealed classes → Pattern matching → Virtual threads → No raw types
- [ ] **DI:** Constructor DI only → No @Autowired fields → No `common/` duplicates
- [ ] **Logging:** @Slf4j present → [ClassName] prefix → Exception as last arg → No creds
- [ ] **Error Handling:** No silent catches → No @SneakyThrows → Generic HTTP responses
- [ ] **Kafka:** Binding via ${kafka.topics.xxx} → No hardcoded topics
- [ ] **Testing:** No Mockito → Real test doubles only
- [ ] **Config & Secrets:** No hardcoded credentials → All via ${ENV_VAR:}

[Read full CodeReviewer checklist in CHECKLISTS.md](CHECKLISTS.md)

---

## 📋 Quick Checklist for Security Audit

**Run [CHECKLISTS.md](CHECKLISTS.md) on all files + scripts + state:**

- [ ] **Credentials:** No tokens in .java, .yaml, .sh, .json, .md
- [ ] **Shell Scripts:** Guard pattern present → env vars only → No literal tokens
- [ ] **API Auth:** Admin endpoints protected (X-Admin-Key or auth)
- [ ] **Input:** Size limits configured → Diffs truncated before logging
- [ ] **Actuator:** Exposes only health,info → Never env, beans, heapdump
- [ ] **Error Responses:** No e.getMessage() in responses → Generic messages
- [ ] **Temp Files:** deleteIfExists in finally → PosixFilePermissions for sensitive files

[Read full Security checklist in CHECKLISTS.md](CHECKLISTS.md)

---

## 📊 Consolidated Files Summary

| File | Lines | Purpose |
|------|-------|---------|
| SHARED-RULES.md | 319 | Non-negotiable constraints |
| CHECKLISTS.md | 332 | Code review + security checklists |
| GenericCodingPractices.md | 248 | Language-agnostic patterns |
| JAVA-CODER.md | 450+ | **NEW: Consolidated Java + Spring + MCP** |
| TESTING-STANDARDS.md | 350+ | **NEW: Consolidated testing standards** |
| MCP-SERVERS.md | 250+ | **NEW: Consolidated MCP guidance** |
| PythonCoder.agent.md | 282 | Python 3.10+ + FastAPI |
| Conductor.agent.md | 258 | Orchestration workflow |
| CodeReviewer.agent.md | 385 | Code review agent |
| Security.agent.md | 176 | Security audit agent |
| Documentation.agent.md | 159 | Documentation maintenance |
| TestPlanner.agent.md | 83 | Test scenario planning |
| Tester.agent.md | 71 | Test execution |
| REFACTORING-SUMMARY.md | 219 | Consolidation documentation |
| **TOTAL** | **4400+** | **Complete unified instruction set** |

---

## ✅ Consolidation Status

- ✓ All `.github/instructions/` content merged (DELETED folder)
- ✓ All `.github/agents/` content reorganized + expanded
- ✓ Zero duplication (centralized in SHARED-RULES, CHECKLISTS, GenericCodingPractices)
- ✓ Language-first architecture (Java, Python separate; scalable to Go, Rust)
- ✓ Caveman ultra mode throughout (compressed for efficiency)
- ✓ Navigable + well-organized
- ✓ Ready for production use

---

## Version
Last updated: 2026-06-16
Consolidation: Complete (all instructions merged, old folders deleted)

---

## 📋 Consolidated Standards (Read These First)

### [SHARED-RULES.md](SHARED-RULES.md) — 380 lines
**What:** All cross-cutting coding standards that apply to everyone (Coder, CodeReviewer, Security).

**Contains:**
- Two Instruction Systems explanation
- 14 Non-Negotiable Constraints
- Java 25 patterns (records, sealed, pattern matching, virtual threads, text blocks)
- Dependency Injection (Spring Boot 4, constructor injection only)
- Logging rules (`@Slf4j`, `[ClassName]` prefix)
- Kafka patterns (producers, consumers, topic binding)
- Redis patterns (`StringRedisTemplate`, key formats, TTL)
- Error handling (no silent catches, exception as last arg)
- Testing — Zero Mockito (real test doubles only)
- Shell script safety (env vars, guard pattern, credential handling)
- Credential safety (code, YAML, scripts, state files, git remotes)
- MCP tool declaration
- Actuator security

**When to use:**
- Coder: Reference for any coding question
- CodeReviewer: Reference for checking code against standards
- Security: Reference for checking credentials/shell scripts

---

### [CHECKLISTS.md](CHECKLISTS.md) — 360 lines
**What:** All review & security audit checklists, severity levels, findings formats.

**Contains:**

#### CodeReviewer Checklist (12 sections, ~150 lines)
1. Java 25 Idioms (10 checks)
2. Spring Boot 4 — Dependency Injection (7 checks)
3. Spring Boot 4 — Bean Lifecycle & Conditionals (5 checks)
4. Logging (5 checks)
5. Error Handling (6 checks)
6. Kafka (7 checks)
7. Redis (6 checks)
8. Testing — Zero Mockito (8 checks)
9. API Design & Controller Layer (6 checks)
10. Configuration & Secrets (5 checks)
11. Performance & Concurrency (5 checks)
12. Code Maintainability (5 checks)

Each section lists: Check | BLOCKER if violated? | (guidance)

#### Security Audit Checklist (10 sections, ~150 lines)
1. Credential & Secret Safety (9 checks)
2. API Authentication (4 checks)
3. Input Validation & Size Limits (4 checks)
4. Actuator Exposure (1 check w/ yaml config)
5. Error Response Sanitisation (3 checks)
6. Dependency & CVE Hygiene (3 checks)
7. ProcessBuilder / Command Execution (4 checks)
8. Redis / Kafka Data Security (3 checks)
9. Secure Headers (1 check w/ yaml config)
10. Temp File Handling (2 checks)

Plus: **Shell Script Security** (special section: 4 explicit checks)

#### Severity Levels (20 lines)
- CodeReviewer: BLOCKER | MAJOR | MINOR | INFO
- Security: CRITICAL | HIGH | MEDIUM | LOW

#### Findings Report Format (20 lines)
- CodeReviewer findings template
- Security findings template

**When to use:**
- CodeReviewer: Run all 12 sections on every changed file
- Security: Run all 10 sections + shell script section on every changed file
- Conductor: Reference when delegating to CodeReviewer / Security

---

## 🎭 Agent Instructions (Role-Specific Workflows)

### [Conductor.agent.md](Conductor.agent.md) — 263 lines
**Role:** Orchestrate feature delivery. Break tasks down, delegate to specialists, enforce gates.

**Unique content:**
- Full workflow: INTAKE → TESTING → FIXING → DONE (11 stages + 2 gates)
- Detailed stage instructions for each agent
- Delegation templates for each agent
- Status file schema (persistent conductor-status.json)
- Module reference (port, responsibility)
- Integration points (when each agent runs, what blocks what)

**Relations:**
- Delegates to → Coder, CodeReviewer, Security, TestPlanner, Tester, Documentation
- References → SHARED-RULES.md (non-negotiable constraints), CHECKLISTS.md (code/security gates)

**Read this if:** You need to understand the full orchestration workflow or troubleshoot a stuck stage.

---

## 🔧 Language-Specific Coders

### [GenericCodingPractices.md](GenericCodingPractices.md) — 150 lines
**What:** Language-agnostic coding best practices shared by all coders.

**Contains:**
- Before writing anything (read code, check `common/`, test after changes)
- Logging rules (prefix with `[ClassName]`, last arg exception)
- Error handling (no silent catches, generic API responses)
- Testing (zero mocking, real test doubles)
- Dependency injection patterns (constructor-based)
- Credential safety (env vars only)
- Shell script safety (guard pattern)
- API/HTTP response rules
- Immutability & data structures
- Naming conventions
- Code organization
- Forbidden patterns (all languages)

**When to use:** Both JavaCoder and PythonCoder reference this file.

---

### [JavaCoder.agent.md](JavaCoder.agent.md) — 150 lines
**Role:** Implement Java/Spring Boot code (Java 25, Spring Boot 4, Kafka/Redis).

**Unique Java-specific content:**
- Java 25 patterns (records, sealed classes, pattern matching, virtual threads, text blocks)
- Spring Boot 4 (constructor injection via Lombok, conditional beans, Kafka producers/consumers, MCP tools)
- Testing (JUnit 5, real test doubles, no Mockito)
- Java-specific forbidden patterns

**References:**
- [GenericCodingPractices.md](GenericCodingPractices.md) (error handling, logging, testing fundamentals)
- [SHARED-RULES.md](SHARED-RULES.md) (Spring patterns, Kafka, Redis, shell scripts)

**Read this if:** You are implementing Java/Spring Boot code.

---

### [PythonCoder.agent.md](PythonCoder.agent.md) — 150 lines
**Role:** Implement Python code (Python 3.10+, FastAPI, async, RAG services).

**Unique Python-specific content:**
- Python 3.10+ patterns (type hints, pattern matching, async/await, dataclasses, f-strings)
- FastAPI patterns (endpoints with logging, dependency injection, error handling)
- Testing (pytest, real test doubles, no mock.patch)
- Python-specific forbidden patterns

**References:**
- [GenericCodingPractices.md](GenericCodingPractices.md) (error handling, logging, DI, testing fundamentals)
- [SHARED-RULES.md](SHARED-RULES.md) (credential safety, shell scripts when needed)

**Read this if:** You are implementing Python code (iWiki services).

---

### [CodeReviewer.agent.md](CodeReviewer.agent.md) — 386 lines
**Role:** Audit every Coder change for correctness. Block on BLOCKER/MAJOR. Forward approval to Security.

**Unique content:**
- 12-section review checklist with BLOCKER/MAJOR rules
- Common violations and fixes (per section, with code examples)
- Severity levels (BLOCKER, MAJOR, MINOR, INFO)
- Findings report format
- Rules (never skip sections, never forward with outstanding BLOCKERs)
- Integration with Conductor workflow (gates, iterations, approvals)

**Relations:**
- References → CHECKLISTS.md (all 12 sections consolidated here now)
- DEPRECATED → Checklist details moved to CHECKLISTS.md (this file still has examples)
- Receives from → Conductor (after Coder BUILD SUCCESS)
- Outputs to → Security (if APPROVED) or back to Coder (if findings)

**Read this if:** You need to review code or understand code review gates.

---

### [Security.agent.md](Security.agent.md) — 177 lines
**Role:** Audit for credentials, API auth, input validation, actuator exposure, temp files, CVEs.

**Unique content:**
- 10-section audit checklist with pass conditions
- Shell script specific rules (guard pattern, env vars, process args)
- Severity levels (CRITICAL, HIGH, MEDIUM, LOW)
- Findings report format
- Rules (never accept hardcoded secrets, fail-secure on auth)
- Integration with Conductor (design review + code review gates)

**Relations:**
- References → CHECKLISTS.md (all 10 sections + shell script section consolidated)
- DEPRECATED → Checklist details moved to CHECKLISTS.md (this file still has context)
- Receives from → Conductor (design stage OR after CodeReviewer APPROVED)
- Outputs to → Documentation (if passed) or back to Coder (if findings)

**Read this if:** You need to audit for security or understand security gates.

---

### [TestPlanner.agent.md](TestPlanner.agent.md) — 84 lines
**Role:** Convert feature descriptions into test scenarios (JUnit 5, no code).

**Unique content:**
- Test scenario planning process
- Test naming convention
- Zero Mockito constraints
- Module placement paths
- Output format (test plan, not implementation)

**Relations:**
- References → SHARED-RULES.md § Testing (zero mockito examples)
- Receives from → Conductor (feature description)
- Outputs to → Coder (test stubs for implementation)

**Read this if:** You need to write test scenarios.

---

### [Tester.agent.md](Tester.agent.md) — 72 lines
**Role:** Run and validate tests. Fix step definitions or page objects if tests fail.

**Unique content:**
- Test execution steps (Maven command, JUnit 5 runner)
- Failure interpretation (compilation, undefined steps, assertions, locators)
- Fix and re-run procedure
- Module-specific file locations (web, mobile)
- Rules (never modify feature files to work around failures)

**Relations:**
- Receives from → Conductor (test & fix iterations)
- May output to → Coder (if test doubles needed) or CodeReviewer

**Read this if:** You need to run tests or troubleshoot test failures.

---

### [Documentation.agent.md](Documentation.agent.md) — 160 lines
**Role:** Update and maintain docs. Keep precise, concise, DRY (one source of truth per concept).

**Unique content:**
- Responsibilities (update ARCHITECTURE.md, README.md after changes)
- Documentation philosophy (link don't duplicate, 3-file max)
- Consolidation checklist
- Update patterns (new feature, config change, performance, troubleshooting)
- Forbidden actions (no agent file writes, no duplication)
- Approval criteria (no section >2000 words, one concept = one location)

**Relations:**
- Receives from → Conductor (after Security APPROVED) for major changes
- References → README.md, ARCHITECTURE.md, `.env.example` (only docs to maintain)

**Read this if:** You need to update documentation.

---

## 🔗 Project Documentation

These are what Documentation agent maintains:

- **[README.md](../../README.md)** — Quick start, environment variables, API reference, local development
- **[ARCHITECTURE.md](../../ARCHITECTURE.md)** — System design, data model, pipeline flows, performance, troubleshooting
- **[.env.example](../../.env.example)** — Environment variable template

---

## 📊 File Cross-References

| Agent | Reads | References |
|-------|-------|-----------|
| **JavaCoder** | Task from Conductor | GenericCodingPractices + SHARED-RULES |
| **PythonCoder** | Task from Conductor | GenericCodingPractices + SHARED-RULES |
| **CodeReviewer** | Code from Coder | CHECKLISTS (CodeReviewer) + SHARED-RULES |
| **Security** | Code from CodeReviewer | CHECKLISTS (Security) + SHARED-RULES |
| **TestPlanner** | Feature from Conductor | SHARED-RULES (testing) |
| **Tester** | Tests from Coder | — |
| **Documentation** | Change summary | Project docs (README, ARCHITECTURE, .env.example) |
| **Conductor** | Feature request | All agents, SHARED-RULES, CHECKLISTS, GenericCodingPractices |

---

## 🚀 For First-Time Users

1. **Understand the workflow:** Read [Conductor.agent.md](Conductor.agent.md) § Standard Workflow
2. **Learn generic practices:** Read [GenericCodingPractices.md](GenericCodingPractices.md) (first 3 sections)
3. **Pick your language & role:**
   - **Writing Java code?** → Read [JavaCoder.agent.md](JavaCoder.agent.md)
   - **Writing Python code?** → Read [PythonCoder.agent.md](PythonCoder.agent.md)
   - **Reviewing code?** → Read [CodeReviewer.agent.md](CodeReviewer.agent.md)
   - **Auditing security?** → Read [Security.agent.md](Security.agent.md)
   - **Creating tests?** → Read [TestPlanner.agent.md](TestPlanner.agent.md)
4. **Reference GenericCodingPractices.md whenever you need common patterns** (logging, error handling, testing, etc.)
5. **Use SHARED-RULES.md for cross-cutting concerns** (shell scripts, credentials, Kafka/Redis)
6. **Use CHECKLISTS.md when reviewing** (12 code sections, 10 security sections)

---

## 📋 Quick Checklist for Code Review

Copy this and use it:

```
CodeReviewer running [CHECKLISTS.md](CHECKLISTS.md) on: [FILES]

File: ___________

## Java 25 Idioms (§1)
- [ ] Records for DTOs
- [ ] Sealed classes present where applicable
- [ ] Pattern matching instead of casts
- [ ] Virtual threads if async
- [ ] No raw types
- [ ] No unchecked casts without @SuppressWarnings

## DI (§2)
- [ ] Constructor injection (@RequiredArgsConstructor)
- [ ] No @Autowired on fields
- [ ] No `common/` duplicates

## Logging (§4)
- [ ] @Slf4j present
- [ ] [ClassName] prefix on all logs
- [ ] Exception as last arg to log.error()
- [ ] No credentials logged

## Error Handling (§5)
- [ ] No silent catches
- [ ] No @SneakyThrows in services
- [ ] HTTP responses generic (no e.getMessage())

## Kafka (§6)
- [ ] Binding via${kafka.topics.xxx}
- [ ] No hardcoded topics

## Testing (§8)
- [ ] No Mockito
- [ ] Real test doubles only

## Config & Secrets (§10)
- [ ] No hardcoded credentials
- [ ] All via ${ENV_VAR:}

Status: APPROVED | NEEDS_FIXES
```

---

## 📋 Quick Checklist for Security Audit

```
Security running [CHECKLISTS.md](CHECKLISTS.md) on: [FILES + scripts + state]

## Credentials (§1)
- [ ] No tokens in .java, .yaml, .sh, .json, .md
- [ ] All via env vars
- [ ] Shell guard pattern present
- [ ] .env in .gitignore
- [ ] No credentials in git remote URLs

## API Auth (§2)
- [ ] Admin endpoints protected (X-Admin-Key or auth)
- [ ] Webhooks verified
- [ ] Fail-secure (not fail-open)

## Input Validation (§3)
- [ ] Size limits configured
- [ ] Diffs truncated before logging

## Actuator (§4)
- [ ] Exposes only health,info
- [ ] Never env, beans, heapdump

## Error Responses (§5)
- [ ] No e.getMessage() in responses
- [ ] Generic error messages

## ProcessBuilder (§7)
- [ ] Use List<String> (not shell string)
- [ ] No tokens in args

## Temp Files (§10)
- [ ] deleteIfExists in finally
- [ ] PosixFilePermissions for sensitive files

Status: CRITICAL | HIGH | MEDIUM | LOW findings
```

---

## Version
Last updated: 2024-06-16
Compression report: [COMPRESSION-REPORT.md](COMPRESSION-REPORT.md)

