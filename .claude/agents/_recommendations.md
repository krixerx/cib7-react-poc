# Recommended Team

Same team as roppoc, adapted 2026-11-02. The differences come from the stack.

## Process modeller

In roppoc this role hand-writes BPMN, DMN and forms. Here they are generated, so it edits nothing by hand: it runs `/service-builder`. That skill replaces roppoc's `user-task-forms` and `service-task-integration`, whose Camunda 8 content does not apply to CIB seven.

## Programmer variants

- `programmer-backend`: the Java modules, the bus and the two Node services.
- `programmer-frontend`: the SPA and the Flutter app.

**Rationale:** roppoc's split carries over. `mcp/` and `pdf-renderer/` go with the server side and `mobile/` with the client side, because each is too small to justify its own persona; their rule tables are separate skills.

## Reviewer

roppoc recommended one and never built it. It is added here, read-only.

## Not ported

`roppoc-test` and `cms-conventions`: this repository has no external ROP specification set and no Strapi.
