package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.cibseven.bpm.dmn.engine.DmnDecision;
import org.cibseven.bpm.dmn.engine.DmnDecisionTableResult;
import org.cibseven.bpm.dmn.engine.DmnEngine;
import org.cibseven.bpm.dmn.engine.DmnEngineConfiguration;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pure DMN-engine tests for the two auto-approval decision tables — no Spring context, no process
 * engine, no database. A standalone DMN engine parses the {@code .dmn} files straight from the
 * classpath and evaluates them with the same variable names the BPMN business-rule tasks use.
 *
 * <p>Both tables use hit policy FIRST with a final catch-all {@code Rule_DefaultReview}, so every
 * possible input combination must produce exactly one {@code autoDecision} entry — the regression
 * tests at the bottom pin that contract for out-of-band inputs.
 */
class DmnEvaluationTest {

  private static final String APPROVE = "approve";
  private static final String REVIEW = "review";

  private static DmnEngine dmnEngine;
  private static DmnDecision vehicleDecision;
  private static DmnDecision businessDecision;

  @BeforeAll
  static void parseDecisions() {
    dmnEngine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
    vehicleDecision = parse("processes/vehicle-registration/vehicle-auto-approval.dmn", "vehicle-auto-approval");
    businessDecision = parse("processes/business-registration/business-auto-approval.dmn", "business-auto-approval");
  }

  private static DmnDecision parse(String resource, String decisionKey) {
    InputStream stream = DmnEvaluationTest.class.getClassLoader().getResourceAsStream(resource);
    assertNotNull(stream, resource + " must be on the test classpath");
    return dmnEngine.parseDecision(decisionKey, stream);
  }

  private static String evaluateVehicle(Object age, Object price, Object vehicleAgeYears) {
    VariableMap variables =
        Variables.createVariables()
            .putValue("age", age)
            .putValue("price", price)
            .putValue("vehicleAgeYears", vehicleAgeYears);
    return singleEntry(dmnEngine.evaluateDecisionTable(vehicleDecision, variables));
  }

  private static String evaluateBusiness(Object age, Object capital, Object residency) {
    VariableMap variables =
        Variables.createVariables()
            .putValue("applicantAge", age)
            .putValue("shareCapital", capital)
            .putValue("applicantResidency", residency);
    return singleEntry(dmnEngine.evaluateDecisionTable(businessDecision, variables));
  }

  /** Hit policy FIRST: every evaluation must yield exactly one result row. */
  private static String singleEntry(DmnDecisionTableResult result) {
    assertEquals(1, result.size(), "expected exactly one matched rule, got " + result.size());
    return result.getSingleEntry();
  }

  // --- vehicle-auto-approval ------------------------------------------------

  @Test
  void vehicleAdultWithCheapOldCarIsAutoApproved() {
    // Rule_LowValueAdultOwner: adult, value < 5000, vehicle >= 10 years old.
    assertEquals(APPROVE, evaluateVehicle(30, 3000.0, 12));
  }

  @Test
  void vehicleUnderageOwnerIsReviewed() {
    // Rule_UnderageOwner wins even when price/age would otherwise approve.
    assertEquals(REVIEW, evaluateVehicle(17, 3000.0, 12));
  }

  @Test
  void vehicleLuxuryPriceCapIsReviewed() {
    // Rule_LuxuryVehicle: >= 50000 EUR, boundary value included.
    assertEquals(REVIEW, evaluateVehicle(40, 50000.0, 12));
  }

  @Test
  void vehicleTooNewForAutoApprovalIsReviewed() {
    // Cheap but < 10 years old misses Rule_LowValueAdultOwner and falls
    // through to Rule_DefaultReview.
    assertEquals(REVIEW, evaluateVehicle(30, 3000.0, 5));
  }

  @Test
  void vehicleMidValueFallsThroughToDefaultReview() {
    // Out-of-band combination matched by no specific rule: adult, mid-value
    // (5000 is NOT < 5000), old vehicle — only Rule_DefaultReview matches.
    assertEquals(REVIEW, evaluateVehicle(30, 5000.0, 20));
  }

  // --- business-auto-approval -----------------------------------------------

  @Test
  void businessAdultCitizenWithSufficientCapitalIsAutoApproved() {
    // Rule_CitizenOrEResidentAdult, capital boundary 2500 included.
    assertEquals(APPROVE, evaluateBusiness(30, 2500.0, "citizen"));
  }

  @Test
  void businessAdultEResidentWithSufficientCapitalIsAutoApproved() {
    assertEquals(APPROVE, evaluateBusiness(25, 5000.0, "e-resident"));
  }

  @Test
  void businessUnderageFounderIsReviewed() {
    assertEquals(REVIEW, evaluateBusiness(17, 5000.0, "citizen"));
  }

  @Test
  void businessBelowMinimumCapitalIsReviewed() {
    assertEquals(REVIEW, evaluateBusiness(30, 1000.0, "citizen"));
  }

  @Test
  void businessForeignFounderIsReviewed() {
    assertEquals(REVIEW, evaluateBusiness(40, 10000.0, "foreign"));
  }

  @Test
  void businessUnexpectedResidencyFallsThroughToDefaultReview() {
    // REGRESSION: before Rule_DefaultReview was added, an adult founder with
    // sufficient capital and an unknown residency value matched NO rule and
    // hit policy FIRST returned an EMPTY result instead of a decision.
    assertEquals(REVIEW, evaluateBusiness(30, 5000.0, "martian"));
  }

  @Test
  void businessMissingResidencyFallsThroughToDefaultReview() {
    // REGRESSION: a null residency matches neither "foreign" nor the
    // citizen/e-resident list; only the catch-all Rule_DefaultReview fires.
    assertEquals(REVIEW, evaluateBusiness(30, 5000.0, null));
  }
}
