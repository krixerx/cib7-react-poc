package com.poc.backend.cases;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.security.CaseAccessService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an applicant throw away a case they opened but never submitted. Opening a service starts the
 * process instance right away, so without this every abandoned form stays in "My cases" for good.
 *
 * <p>A case is a draft while it is active and none of its user tasks has been completed. Only the
 * user who started it may delete it; reviewers read every case but act as the applicant on none.
 * Applicants hold no DELETE grant in the engine, so the delete runs here with the service account
 * after these checks. Non-owners and unknown ids get the same 404, a submitted case gets 409.
 */
@RestController
@RequestMapping("/api/cases")
public class DraftCaseController {

  private static final Logger log = LoggerFactory.getLogger(DraftCaseController.class);

  private final CaseAccessService caseAccess;
  private final EngineClient engine;

  public DraftCaseController(CaseAccessService caseAccess, EngineClient engine) {
    this.caseAccess = caseAccess;
    this.engine = engine;
  }

  @GetMapping("/drafts")
  public ResponseEntity<?> drafts() {
    String me = caseAccess.callerUsername();
    if (me == null) {
      return ResponseEntity.ok(new DraftsResponse(List.of()));
    }
    List<String> ids =
        engine.unfinishedInstanceIdsStartedBy(me).stream()
            .filter(id -> engine.finishedTaskCount(id) == 0)
            .toList();
    return ResponseEntity.ok(new DraftsResponse(ids));
  }

  @DeleteMapping("/{processInstanceId}")
  public ResponseEntity<?> delete(@PathVariable String processInstanceId) {
    if (!caseAccess.isCaseStarter(processInstanceId)
        || engine.findActiveById(processInstanceId) == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(new ErrorResponse("not_found", "No such case."));
    }
    if (engine.finishedTaskCount(processInstanceId) > 0) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(new ErrorResponse("not_a_draft", "The case has been submitted already."));
    }
    engine.deleteProcessInstance(processInstanceId);
    log.info("Deleted draft case {}.", processInstanceId);
    return ResponseEntity.noContent().build();
  }

  public record DraftsResponse(List<String> processInstanceIds) {}

  public record ErrorResponse(String error, String message) {}
}
