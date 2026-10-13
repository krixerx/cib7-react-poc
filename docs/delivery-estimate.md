# Delivery estimate: 45 new services

**When to read this:** when planning a rollout of new services on this stack
and you need an order of magnitude for hours, roles and calendar time.

**Contents**
1. [Summary](#summary)
2. [Why most of the effort is not development](#why-most-of-the-effort-is-not-development)
3. [Hours per service](#hours-per-service)
4. [Hours by role](#hours-by-role)
5. [Team and timeline](#team-and-timeline)
6. [What could make it bigger](#what-could-make-it-bigger)
7. [Next steps](#next-steps)

---

## Summary

Developing, testing and launching 45 new services takes about **10,000–14,000
hours** of work, including a one-off 1,000–2,000 hours to make the platform
production-ready. That is **9–12 months** from the start until the last
service is live, with a team of 9–10 people delivering in waves.

This is a planning estimate made on 2026-10-13, not a quote. The 45 services
have not been analysed yet. The estimate is calibrated against the two
services of the reference pack, vehicle registration and business
registration. Each of those has about 1,700 lines of specification, 2 forms,
10–12 automated steps and 1 decision table. Ministry staff time comes on top:
about 15–20 hours per service for workshops and acceptance testing.

## Why most of the effort is not development

Services on this platform are specification-first. An analyst writes each
service as a structured description of its forms, steps, fees, documents and
decision rules. The `/service-builder` skill then generates the process
model, decision tables, forms, document templates and access rules from that
description.

The coding per service is therefore small. The hours go into four other
places: agreeing the process with the ministry, connecting to external
systems, testing, and training the people who will use it.

## Hours per service

A service takes 80–300 hours depending on its complexity. The estimate
assumes 15 simple, 20 medium and 10 complex services, which gives about
7,200 hours of per-service work before shared costs.

| Complexity | Typical shape | Hours per service | Assumed count | Hours |
|---|---|---|---|---|
| Simple | 1 form, no external lookup, fixed fee | ~80 | 15 | ~1,200 |
| Medium | 2 forms, a decision table, generated PDFs, co-signing, payment (like vehicle registration) | ~150 | 20 | ~3,000 |
| Complex | Several roles or review rounds, multiple integrations, legal edge cases | ~300 | 10 | ~3,000 |

The mix is an assumption. Classifying the real 45 services will change this
table more than anything else.

## Hours by role

Analysis and testing together take almost 40% of the hours; development and
integrations take about a quarter.

| Role | What they do | Hours, all 45 services |
|---|---|---|
| Business analyst | Workshops with the ministry, legal basis, forms, fees, documents, decision rules, writing the service specification | ~2,250 (about 50 per service) |
| QA / tester | Test cases, functional and regression testing, automated end-to-end tests, support during acceptance testing | ~1,800 |
| Developer | Generating the service, reviewing the output, designing PDF and email templates, custom forms where needed, defect fixing | ~1,350 |
| Integration developer | Connections to external registries, identity and payment systems; shared across services | 800–1,500 |
| Project manager / product owner | Planning, ministry coordination, sign-offs | ~1,000 |
| Trainer / technical writer | Guides for civil servants and applicants, training sessions | ~750 |
| DevOps / release | Release pipeline, wave go-lives, support after each go-live | 400–600 |
| Translator | Texts in every supported language (currently English and Arabic) | ~270 |
| Security reviewer | Review of each wave against [`security.md`](security.md), plus a penetration test | ~150 |
| Platform engineer (one-off) | Production readiness: high availability, backups, monitoring, data protection, performance testing | 1,000–2,000 |
| **Total** | | **~10,700** (10,000–14,000) |

## Team and timeline

The proposed team is 3 business analysts, 2 developers, 1 integration
developer, 2–3 testers, 1 trainer, half a DevOps engineer and a project
manager. At full pace it delivers 1.5–2 services a week. The production
readiness work falls in the first two months and needs 2–3 platform
engineers on top of that team.

| Phase | Months | Services live at the end |
|---|---|---|
| Inventory, integration analysis, platform hardening | 1–2 | – |
| Pilot: 3–5 simple services | 2–4 | 3–5 |
| Wave 1: ~10 services | 4–6 | ~15 |
| Wave 2: ~10 services | 6–8 | ~25 |
| Wave 3: ~10 services | 8–10 | ~35 |
| Wave 4: the last complex services | 9–12 | 45 |
| Hypercare | 11–12 | – |

The phases overlap: analysis of the next wave starts before the current one
goes live, and training and acceptance testing run in the weeks before each
go-live. The pilot is the checkpoint, since its measured throughput corrects
this estimate before the waves start. The pace is set by the analysts and by
how quickly the ministry decides, so adding developers would not shorten the
timeline.

## What could make it bigger

- **External integrations.** Each new external system costs 40–120 hours and
  often waits on the other party. This is the most uncertain line in the
  estimate.
- **Production readiness.** The platform is currently a proof of concept.
  High availability, backups, monitoring, data protection and performance
  testing are budgeted at 1,000–2,000 hours, but the real gap is unknown
  until the platform is reviewed against the production requirements.
- **Process redesign.** If ministries want to simplify their processes before
  automating them, rather than digitising them as they are, analysis time can
  double.
- **Ministry availability.** Slow decisions or sign-offs stretch the calendar
  even when the hours stay the same.

## Next steps

Two inputs would turn this into a per-service estimate with a much narrower
range:

- Classify each of the 45 services as simple, medium or complex.
- List the external systems each service needs, and whether an interface to
  them already exists.
