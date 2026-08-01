"""API pack: safe client skeleton and deterministic contract checks."""

from __future__ import annotations

import re

from qa_engine_models import (
    AutomationTask,
    GeneratedArtifact,
    LocalContextBundle,
    RepairType,
    TestDomain,
    ValidationResult,
    java_pascal_identifier,
)


class ApiPack:
    domain = TestDomain.API
    policy_rules = (
        "lazy-config-validation",
        "input-guards",
        "EnvGuard-writes-only",
        "explicit-success-contract",
        "delete-response-logging",
        "lowercase-response-keys",
    )

    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        component = java_pascal_identifier(
            task.impacted_components[0] if task.impacted_components else "Generated"
        )
        gaps = []
        endpoint = context.endpoints[0] if context.endpoints else ""
        if not endpoint:
            gaps.append("no local endpoint evidence; endpoint remains unresolved")
        content = (
            f"package {context.target_package};\n\n"
            "import static java.util.stream.Collectors.toMap;\n\n"
            "import java.util.Locale;\n"
            "import java.util.Map;\n"
            "import lombok.extern.slf4j.Slf4j;\n"
            "import com.example.utils.EnvGuard;\n\n"
            "@Slf4j\n"
            f"public class {component}ApiSupport {{\n"
            "    private final String baseUrl;\n\n"
            f"    public {component}ApiSupport(String baseUrl) {{\n"
            "        this.baseUrl = baseUrl;\n"
            "        // Configuration is validated lazily by API operations.\n"
            "    }\n\n"
            "    public String getResource(String id) {\n"
            "        requireValue(id, \"id\");\n"
            "        requireConfigured();\n"
            f"        // TODO: call locally verified GET endpoint {endpoint or '<unresolved>'}.\n"
            "        return \"TODO\";\n"
            "    }\n\n"
            "    public String createResource(String payload) {\n"
            "        requireValue(payload, \"payload\");\n"
            "        requireConfigured();\n"
            "        EnvGuard.ensureEnvSafety();\n"
            "        String response = \"TODO\";\n"
            "        if (!isExplicitSuccess(response)) {\n"
            "            throw new IllegalStateException(\"Unexpected API success contract\");\n"
            "        }\n"
            "        return response;\n"
            "    }\n\n"
            "    public void deleteResource(String id) {\n"
            "        requireValue(id, \"id\");\n"
            "        requireConfigured();\n"
            "        EnvGuard.ensureEnvSafety();\n"
            "        String response = \"TODO\";\n"
            " if (!isExplicitSuccess(response)) {\n"
            "     throw new IllegalStateException(\"Unexpected API success contract\");\n"
            " }\n"
            " log.info(\"Delete response: {}\", response);\n"
            " }\n\n"
            "    private void requireConfigured() {\n"
            "        if (baseUrl == null || baseUrl.isBlank()) {\n"
            "            throw new IllegalStateException(\"API base URL is required\");\n"
            "        }\n"
            "    }\n\n"
            "    private void requireValue(String value, String name) {\n"
            "        if (value == null || value.isBlank()) {\n"
            "            throw new IllegalArgumentException(name + \" must not be blank\");\n"
            "        }\n"
            "    }\n\n"
            "    private boolean isExplicitSuccess(String response) {\n"
            "        return \"OK\".equals(response) || \"true\".equals(response)\n"
            "            || response.matches(\"[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}\");\n"
            "    }\n\n"
            "    private Map<String, Object> lowercaseKeys(Map<String, Object> response) {\n"
            "        return response.entrySet().stream().collect(toMap(\n"
            "            entry -> entry.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));\n"
            "    }\n"
            "}\n"
        )
        return GeneratedArtifact(
            "api-client",
            content,
            confidence_score=0.8 if endpoint else 0.6,
            unresolved_gaps=gaps,
        )

    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        if artifact.kind != "api-client":
            return ValidationResult(True)
        content = artifact.content
        errors: list[str] = []
        if re.search(
            r"public\s+\w+\s*\([^)]*\)\s*\{[^}]*throw new RuntimeException",
            content,
            re.DOTALL,
        ):
            errors.append("API constructor must use lazy configuration validation")
        if re.search(r"(?:!|==\s*false).*?(?:isEmpty|isBlank)|length\(\)\s*>\s*0", content):
            errors.append("API success must not rely on a non-empty response body")
        if "isExplicitSuccess(" not in content:
            errors.append("API success must use an explicit success contract")
        if "toLowerCase(Locale.ROOT)" not in content:
            errors.append("API response-map keys must be normalized to lowercase")
        if re.search(r"\bMap\s*<", content) and "import java.util.Map;" not in content:
            errors.append("API Map references require java.util.Map import")
        if "Locale.ROOT" in content and "import java.util.Locale;" not in content:
            errors.append("API Locale references require java.util.Locale import")
        if re.search(r"\btoMap\s*\(", content) and (
            "import static java.util.stream.Collectors.toMap;" not in content
        ):
            errors.append("API toMap references require Collectors static import")
        if "EnvGuard.ensureEnvSafety()" in content and (
            "import com.example.utils.EnvGuard;" not in content
        ):
            errors.append(
                "API EnvGuard references require com.example.utils.EnvGuard import"
            )
        if re.search(r"\blog\.\w+\s*\(", content):
            if "@Slf4j" not in content:
                errors.append("API logging requires @Slf4j")
            if "import lombok.extern.slf4j.Slf4j;" not in content:
                errors.append("API logging requires Slf4j import")
        throwable_names = set(
            re.findall(
                r"\b(?:\w*(?:Exception|Throwable|Error)|Throwable|Exception)\s+"
                r"([A-Za-z_]\w*)\b",
                content,
            )
        )
        if any(
            not self._is_throwable_argument(
                self._last_argument(arguments), throwable_names
            )
            for arguments in self._log_error_arguments(content)
        ):
            errors.append("API log.error must pass Throwable as final argument")
        for name, parameters, body in self._public_methods(content):
            lowered = name.lower()
            if parameters and not (
                "IllegalArgumentException" in body or "requireValue(" in body
            ):
                errors.append(f"API method {name} requires input guards")
            if lowered.startswith(
                ("create", "update", "put", "post", "delete", "patch")
            ):
                if "EnvGuard.ensureEnvSafety()" not in body:
                    errors.append(f"API write method {name} requires EnvGuard")
                if not self._has_explicit_success_check(body):
                    errors.append(
                        f"API write method {name} requires explicit success check"
                    )
            if lowered.startswith(("get", "find", "list", "read")) and (
                "EnvGuard.ensureEnvSafety()" in body
            ):
                errors.append(f"API read method {name} must not use EnvGuard")
            if lowered.startswith("delete") and not self._logs_response_value(body):
                errors.append(f"API delete method {name} must log full response")
        return ValidationResult(
            not errors,
            errors,
            suggested_repair_type=(
                RepairType.HUMAN_REVIEW if errors else RepairType.LOCAL_FIX
            ),
        )

    @staticmethod
    def _has_explicit_success_check(body: str) -> bool:
        response_names = re.findall(
            r"\b(?:String|Response|HttpResponse)\s+(\w*response\w*)\s*=",
            body,
            re.IGNORECASE,
        )
        if not re.search(r"\bif\s*\(\s*!?\s*isExplicitSuccess\s*\(", body):
            return False
        return not response_names or any(
            re.search(
                rf"\bisExplicitSuccess\s*\(\s*{re.escape(name)}\s*\)",
                body,
            )
            for name in response_names
        )

    @staticmethod
    def _log_error_arguments(content: str) -> list[str]:
        return ApiPack._log_arguments(content, "error")

    @staticmethod
    def _logs_response_value(body: str) -> bool:
        return any(
            "response" in ApiPack._arguments(arguments)
            for arguments in ApiPack._log_arguments(body)
        )

    @staticmethod
    def _log_arguments(content: str, level: str | None = None) -> list[str]:
        calls: list[str] = []
        method = re.escape(level) if level else r"\w+"
        for match in re.finditer(rf"\blog\.{method}\s*\(", content):
            depth = 1
            quote = ""
            for index in range(match.end(), len(content)):
                char = content[index]
                if quote:
                    if char == "\\":
                        continue
                    if char == quote:
                        quote = ""
                elif char in {"'", '"'}:
                    quote = char
                elif char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                    if depth == 0:
                        calls.append(content[match.end():index])
                        break
        return calls

    @staticmethod
    def _arguments(arguments: str) -> list[str]:
        values: list[str] = []
        depth = 0
        quote = ""
        start = 0
        for index, char in enumerate(arguments):
            if quote:
                if char == quote and (
                    index == 0 or arguments[index - 1] != "\\"
                ):
                    quote = ""
            elif char in {"'", '"'}:
                quote = char
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            elif char == "," and depth == 0:
                values.append(arguments[start:index].strip())
                start = index + 1
        values.append(arguments[start:].strip())
        return values

    @staticmethod
    def _last_argument(arguments: str) -> str:
        return ApiPack._arguments(arguments)[-1]

    @staticmethod
    def _is_throwable_argument(argument: str, throwable_names: set[str]) -> bool:
        return argument in throwable_names or bool(
            re.fullmatch(
                r"new\s+[\w.]*?(?:Exception|Throwable|Error)\s*\([^)]*\)",
                argument,
            )
        )

    @staticmethod
    def _public_methods(content: str) -> list[tuple[str, str, str]]:
        pattern = re.compile(
            r"\bpublic\s+"
            r"(?:(?:static|final|synchronized|abstract|default|native|strictfp)\s+)*"
            r"(?:<[^>{}()]+>\s+)?"
            r"[\w.$?]+(?:\s*<[^;{}()]+>)?(?:\s*\[\])*\s+"
            r"(?P<name>\w+)\s*\((?P<parameters>[^)]*)\)\s*"
            r"(?:throws\s+[^{]+)?\{"
        )
        methods: list[tuple[str, str, str]] = []
        for match in pattern.finditer(content):
            depth = 0
            end = match.end()
            for index in range(match.end() - 1, len(content)):
                if content[index] == "{":
                    depth += 1
                elif content[index] == "}":
                    depth -= 1
                    if depth == 0:
                        end = index + 1
                        break
            methods.append(
                (
                    match.group("name"),
                    match.group("parameters").strip(),
                    content[match.start():end],
                )
            )
        return methods
