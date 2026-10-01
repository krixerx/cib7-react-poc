package com.poc.cib7.policy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The top-level process variables a client may write for one process definition: once when it
 * starts the process, and once per user-task form. Loaded from {@code
 * processes/<service>/variable-policy.json}, which the service builder generates from the spec.
 *
 * @param processDefinitionKey the BPMN process id the policy applies to
 * @param start variables a start request may carry
 * @param forms form id (the task's {@code formKey} without the {@code react:} prefix) to the
 *     variables a completion of that form may carry
 */
public record VariablePolicy(
    String processDefinitionKey, Set<String> start, Map<String, Set<String>> forms) {

  /** Prefix the SPA's form registry uses in {@code camunda:formKey}. */
  static final String REACT_PREFIX = "react:";

  public VariablePolicy {
    start = Set.copyOf(start);
    forms = Map.copyOf(forms);
  }

  /** The allowlist for a task's {@code formKey}, or empty if the policy has no entry for it. */
  public Optional<Set<String>> form(String formKey) {
    if (formKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(forms.get(formId(formKey)));
  }

  /**
   * Strips the {@code react:} prefix, so policies are keyed by the same id as the form registry.
   */
  public static String formId(String formKey) {
    return formKey.startsWith(REACT_PREFIX) ? formKey.substring(REACT_PREFIX.length()) : formKey;
  }
}
