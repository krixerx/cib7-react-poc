/**
 * Service catalog metadata for the PartA applicant landing.
 *
 * Process definitions ship without a category tag in the BPMN model, so the
 * frontend keeps a static map from process-definition key to category. Add a
 * new service: register its key here too. Unknown keys fall through to
 * `other` so the catalog never hides a deployed service.
 *
 * The icon name maps to an inline SVG in ServicesPage.tsx (CATEGORY_ICONS) —
 * keep that switch and these ids in sync.
 */

export type CategoryId =
  | 'business'
  | 'family'
  | 'property'
  | 'travel'
  | 'social'
  | 'other';

export interface Category {
  id: CategoryId;
  name: string;
  /** One-line, plain-language hint shown under the tile name. */
  blurb: string;
}

export const CATEGORIES: Category[] = [
  { id: 'business', name: 'Business & Trade',       blurb: 'Register a business, change ownership, file declarations.' },
  { id: 'family',   name: 'Family & Civil Status',  blurb: 'Birth, marriage, name changes, family certificates.' },
  { id: 'property', name: 'Property & Land',        blurb: 'Land titles, transfers, building permits.' },
  { id: 'travel',   name: 'Travel & Identity',      blurb: 'Passports, ID cards, residence permits.' },
  { id: 'social',   name: 'Social & Health',        blurb: 'Benefits, health entitlements, support requests.' },
  { id: 'other',    name: 'Other Services',         blurb: 'Anything that does not fit the categories above.' },
];

/**
 * Process definition key → category. Add new services here as the BPMN model
 * grows. Keys not listed fall through to `other`.
 */
const SERVICE_CATEGORY: Record<string, CategoryId> = {
  businessRegistration: 'business',
  personRegistration: 'family',
};

export function categoryOf(processDefinitionKey: string): CategoryId {
  return SERVICE_CATEGORY[processDefinitionKey] ?? 'other';
}
