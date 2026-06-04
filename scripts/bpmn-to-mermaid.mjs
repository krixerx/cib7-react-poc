#!/usr/bin/env node
// BPMN → mermaid flowchart converter.
//
// Reads a CIB seven / Camunda 7 BPMN file and emits a `flowchart LR` mermaid
// diagram. Output modes: stdout (default), .mmd file, or in-place replacement
// of a marker block in a markdown file.
//
// Marker block format expected in the target .md:
//   <!-- bpmn-diagram:start -->
//   (replaced on each run)
//   <!-- bpmn-diagram:end -->

import { XMLParser } from 'fast-xml-parser';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const MARKER_START = '<!-- bpmn-diagram:start -->';
const MARKER_END = '<!-- bpmn-diagram:end -->';

const TASK_KINDS = new Set([
  'userTask', 'serviceTask', 'businessRuleTask',
  'sendTask', 'receiveTask', 'manualTask', 'task', 'scriptTask', 'callActivity',
  'subProcess',
]);
const GATEWAY_KINDS = new Set([
  'exclusiveGateway', 'parallelGateway', 'inclusiveGateway', 'eventBasedGateway',
]);
const EVENT_KINDS = new Set([
  'startEvent', 'endEvent', 'intermediateCatchEvent', 'intermediateThrowEvent',
]);

function parseArgs(argv) {
  const args = { input: null, out: null, help: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--out' || a === '-o') args.out = argv[++i];
    else if (a === '--help' || a === '-h') args.help = true;
    else if (!args.input) args.input = a;
  }
  return args;
}

function usage() {
  return [
    'Usage: node bpmn-to-mermaid.mjs <bpmn-file> [--out <file>]',
    '',
    '  --out <file.mmd>    Write standalone mermaid file',
    '  --out <file.md>     Replace block between',
    '                      <!-- bpmn-diagram:start --> and <!-- bpmn-diagram:end -->',
    '                      in the target markdown file',
    '  (no --out)          Print mermaid to stdout',
  ].join('\n');
}

function toArr(x) {
  if (x == null) return [];
  return Array.isArray(x) ? x : [x];
}

function quoteLabel(s) {
  return '"' + String(s).replace(/"/g, '&quot;') + '"';
}

function shape(kind, id, label) {
  const L = quoteLabel(label);
  switch (kind) {
    case 'startEvent':              return `${id}((${L}))`;
    case 'endEvent':                return `${id}(((${L})))`;
    case 'intermediateCatchEvent':  return `${id}((${L}))`;
    case 'intermediateThrowEvent':  return `${id}((${L}))`;
    case 'boundaryEvent':           return `${id}((${quoteLabel('⏱ ' + label)}))`;
    case 'userTask':                return `${id}[${quoteLabel('👤 ' + label)}]`;
    case 'serviceTask':             return `${id}[[${quoteLabel('🔌 ' + label)}]]`;
    case 'sendTask':                return `${id}[[${quoteLabel('✉ ' + label)}]]`;
    case 'receiveTask':             return `${id}[[${quoteLabel('📥 ' + label)}]]`;
    case 'businessRuleTask':        return `${id}[/${quoteLabel('📋 ' + label)}/]`;
    case 'scriptTask':              return `${id}[[${quoteLabel('📜 ' + label)}]]`;
    case 'manualTask':              return `${id}[${quoteLabel('✋ ' + label)}]`;
    case 'callActivity':            return `${id}[[${quoteLabel('↪ ' + label)}]]`;
    // Embedded subprocess (incl. multi-instance). Rendered as a subroutine
    // box like callActivity but with a distinct prefix so readers can tell
    // them apart in the legend.
    case 'subProcess':              return `${id}[[${quoteLabel('⊞ ' + label)}]]`;
    case 'task':                    return `${id}[${L}]`;
    case 'exclusiveGateway':        return `${id}{${L}}`;
    case 'parallelGateway':         return `${id}{{${quoteLabel('+ ' + label)}}}`;
    case 'inclusiveGateway':        return `${id}{${quoteLabel('○ ' + label)}}`;
    case 'eventBasedGateway':       return `${id}{${quoteLabel('◇ ' + label)}}`;
    default:                        return `${id}[${L}]`;
  }
}

function extractProcess(doc) {
  const defs = doc.definitions;
  if (!defs) throw new Error('No <definitions> root element found');
  const processes = toArr(defs.process);
  if (!processes.length) throw new Error('No <process> element found');
  return processes.find(p => p.isExecutable === 'true') || processes[0];
}

function buildModel(proc) {
  const nodes = [];
  const edges = [];
  const defaultFlowIds = new Set();

  for (const [key, value] of Object.entries(proc)) {
    if (TASK_KINDS.has(key) || GATEWAY_KINDS.has(key) || EVENT_KINDS.has(key)) {
      for (const el of toArr(value)) {
        nodes.push({ id: el.id, kind: key, name: el.name || el.id });
        if (el.default) defaultFlowIds.add(el.default);
      }
    } else if (key === 'boundaryEvent') {
      for (const el of toArr(value)) {
        nodes.push({
          id: el.id,
          kind: 'boundaryEvent',
          name: el.name || (el.timerEventDefinition ? 'Timer' : 'Boundary'),
          attachedTo: el.attachedToRef,
          interrupting: el.cancelActivity !== 'false',
        });
      }
    } else if (key === 'sequenceFlow') {
      for (const el of toArr(value)) {
        let condition = null;
        if (el.conditionExpression != null) {
          condition = typeof el.conditionExpression === 'string'
            ? el.conditionExpression
            : el.conditionExpression['#text'] || null;
        }
        edges.push({
          id: el.id,
          source: el.sourceRef,
          target: el.targetRef,
          name: el.name || null,
          condition,
          isDefault: false,
        });
      }
    }
  }

  for (const e of edges) {
    if (defaultFlowIds.has(e.id)) e.isDefault = true;
  }
  return { nodes, edges };
}

function renderMermaid(model, processName) {
  const lines = ['flowchart LR'];
  if (processName) lines.push(`  %% ${processName}`);

  for (const n of model.nodes) {
    lines.push('  ' + shape(n.kind, n.id, n.name));
  }

  for (const n of model.nodes) {
    if (n.kind === 'boundaryEvent' && n.attachedTo) {
      const verb = n.interrupting ? 'attached' : 'attached (non-interrupting)';
      lines.push(`  ${n.attachedTo} -. ${verb} .-> ${n.id}`);
    }
  }

  for (const e of model.edges) {
    if (e.isDefault) {
      const lbl = e.name ? `${e.name} (default)` : 'default';
      lines.push(`  ${e.source} -. ${quoteLabel(lbl)} .-> ${e.target}`);
    } else if (e.name) {
      lines.push(`  ${e.source} -- ${quoteLabel(e.name)} --> ${e.target}`);
    } else {
      lines.push(`  ${e.source} --> ${e.target}`);
    }
  }
  return lines.join('\n') + '\n';
}

function wrapInBlock(mermaid) {
  return '```mermaid\n' + mermaid + '```\n';
}

function replaceMarkerBlock(md, content) {
  const startIdx = md.indexOf(MARKER_START);
  const endIdx = md.indexOf(MARKER_END);
  if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) {
    throw new Error(
      `Markers not found in target file. Expected both lines:\n  ${MARKER_START}\n  ${MARKER_END}`,
    );
  }
  const before = md.slice(0, startIdx + MARKER_START.length);
  const after = md.slice(endIdx);
  return before + '\n' + content + after;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.input) {
    console.error(usage());
    process.exit(args.help ? 0 : 1);
  }

  const xml = readFileSync(resolve(args.input), 'utf8');
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: '',
    removeNSPrefix: true,
    textNodeName: '#text',
  });
  const doc = parser.parse(xml);
  const proc = extractProcess(doc);
  const model = buildModel(proc);
  const mermaid = renderMermaid(model, proc.name || proc.id);

  if (!args.out) {
    process.stdout.write(mermaid);
    return;
  }
  if (args.out.endsWith('.md')) {
    const md = readFileSync(args.out, 'utf8');
    writeFileSync(args.out, replaceMarkerBlock(md, wrapInBlock(mermaid)));
    console.error(`Updated diagram block in ${args.out}`);
  } else {
    writeFileSync(args.out, mermaid);
    console.error(`Wrote ${args.out}`);
  }
}

main();
