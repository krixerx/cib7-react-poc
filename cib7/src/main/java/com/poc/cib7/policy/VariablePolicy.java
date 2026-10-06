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
 * @param identity variable to the account attribute it holds ({@code givenName}, {@code
 *     familyName}, {@code email}): set from the signed-in user at start and checked on every
 *     completion, so the applicant cannot change it (identity package)
 */
public record VariablePolicy(
    String processDefinitionKey,
    Set<String> start,
    Map<String, Set<String>> forms,
    Map<String, String> identity) {

  /** The account attributes an identity variable may hold. */
  public static final Set<String> IDENTITY_SOURCES = Set.of("givenName", "familyName", "email");

  /** Prefix the SPA's form registry uses in {@code camunda:formKey}. */
  static final String REACT_PREFIX = "react:";

  public VariablePolicy {
    start = Set.copyOf(start);
    forms = Map.copyOf(forms);
    identity = Map.copyOf(identity);
  }

  /** A policy without identity variables. */
  public VariablePolicy(
      String processDefinitionKey, Set<String> start, Map<String, Set<String>> forms) {
    this(processDefinitionKey, start, forms, Map.of());
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
