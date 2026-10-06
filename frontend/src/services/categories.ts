/**
 * The life-event categories of the PartA applicant landing (core). Which
 * service belongs to which category is the service pack's business: its
 * `catalog.json` says so, read by pack/catalog.ts. Unknown keys fall through
 * to `other` so the page never hides a deployed service.
 *
 * Each id maps to a Lucide icon in CategoryIcon.tsx; keep the two in sync.
 */

import type { CategoryId } from '../pack/catalog';

export type { CategoryId } from '../pack/catalog';
export { categoryOf } from '../pack/catalog';

export interface Category {
  id: CategoryId;
  name: string;
  /** One-line, plain-language hint shown under the tile name. */
  blurb: string;
}

export const CATEGORIES: Category[] = [
  {
    id: 'business',
    name: 'Business & Trade',
    blurb: 'Register a business, change ownership, file declarations.',
  },
  {
    id: 'family',
    name: 'Family & Civil Status',
    blurb: 'Birth, marriage, name changes, family certificates.',
  },
  { id: 'property', name: 'Property & Land', blurb: 'Land titles, transfers, building permits.' },
  {
    id: 'travel',
    name: 'Travel & Identity',
    blurb: 'Passports, ID cards, vehicle registrations, residence permits.',
  },
  {
    id: 'social',
    name: 'Social & Health',
    blurb: 'Benefits, health entitlements, support requests.',
  },
  {
    id: 'other',
    name: 'Other Services',
    blurb: 'Anything that does not fit the categories above.',
  },
];
