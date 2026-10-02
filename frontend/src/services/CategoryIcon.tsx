import {
  BriefcaseBusiness,
  CarFront,
  FileText,
  HeartHandshake,
  House,
  Users,
  type LucideIcon,
} from 'lucide-react';
import type { CategoryId } from './categories';

const ICONS: Record<CategoryId, LucideIcon> = {
  business: BriefcaseBusiness,
  family: Users,
  property: House,
  travel: CarFront,
  social: HeartHandshake,
  other: FileText,
};

/**
 * Lucide icon per service category. Shared by ServicesPage (life-event
 * tiles), MyProcessesPage and the case header so a service always reads with
 * the same glyph as the tile the user clicked.
 */
export function CategoryIcon({ id, size = 24 }: { id: CategoryId; size?: number }) {
  const Icon = ICONS[id];
  return <Icon size={size} aria-hidden="true" />;
}
